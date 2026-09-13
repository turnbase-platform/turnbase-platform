package io.turnbase.platform.room;

import io.turnbase.platform.common.AuthenticatedUser;
import io.turnbase.platform.websocket.SubscriptionAuthorizer;
import io.turnbase.platform.websocket.SubscriptionDecision;
import io.turnbase.platform.websocket.WebSocketConnectionRegistry;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

/**
 * {@code websocket.md} § Authorization / {@code room-websocket.md} § Authorization Model: governs
 * {@code SUBSCRIBE} to {@code /topic/room.{roomId}} — the bare room topic only, not the chat or
 * team sub-topics (`IMP-M2-11` owns those). Two checks, in order: room membership (member or
 * spectator, silently dropped if neither, per {@code websocket.md} § Authorization), then the
 * subscribe-time ownership re-verification `room-lifecycle.md` §1.7 requires — a client can land
 * here between a REST join and its own {@code SUBSCRIBE} after another pod's reconciliation sweep
 * took the room, so holding the socket is never itself evidence of owning the client's room.
 *
 * <p>Also tracks which (room, user) a STOMP session is subscribed to, for two purposes: {@link
 * #onRoomOwnershipLost} severs exactly that room's sockets ({@code room.RoomOwnershipLostEvent}
 * carries no session ids, and this is the only place that knows the mapping), and {@link
 * #onSessionDisconnect} feeds a genuine socket loss into {@code IMP-M2-07}'s disconnect handling
 * (`room-lifecycle.md` §3.1). The two are the same map precisely so {@link #onRoomOwnershipLost}
 * can remove a session from it *before* severing the socket: by the time the resulting {@code
 * SessionDisconnectEvent} fires (asynchronously, after the transport layer notices the closed
 * connection), the entry is already gone and {@link #onSessionDisconnect} correctly treats it as an
 * instance-initiated close, not a player disconnect — §1.6's "the disconnect handler returns early
 * on a tagged session," implemented as tag-by-absence rather than a separate flag, since this map
 * is already the one place that knows the (room, user) a session belongs to. A revoked or
 * rate-limited session is deliberately *not* pre-removed here — {@code
 * WebSocketConnectionRegistry#closeSession} unregisters the user's identity for its own,
 * session-scoped purposes, but this room-membership map is untouched, so that socket's eventual
 * disconnect is still processed as a genuine involuntary disconnect (FR-DC-001) — it is one.
 */
@Component
class RoomSubscriptionAuthorizer implements SubscriptionAuthorizer {

  private static final String ROOM_TOPIC_PREFIX = "/topic/room.";

  /** Matches {@code room-rest-api.md} §7's REST value — same protocol, same retry budget. */
  private static final long RETRY_AFTER_MS = 2000;

  private final RoomRepository repository;
  private final RoomOwnershipService ownership;
  private final WebSocketConnectionRegistry connectionRegistry;
  private final RoomService roomService;
  private final Map<UUID, Set<String>> sessionsByRoom = new ConcurrentHashMap<>();
  private final Map<String, RoomMembership> membershipBySession = new ConcurrentHashMap<>();

  RoomSubscriptionAuthorizer(
      RoomRepository repository,
      RoomOwnershipService ownership,
      WebSocketConnectionRegistry connectionRegistry,
      RoomService roomService) {
    this.repository = repository;
    this.ownership = ownership;
    this.connectionRegistry = connectionRegistry;
    this.roomService = roomService;
  }

  private record RoomMembership(UUID roomId, UUID userId) {}

  @Override
  public boolean supports(String destination) {
    if (!destination.startsWith(ROOM_TOPIC_PREFIX)) {
      return false;
    }
    String rest = destination.substring(ROOM_TOPIC_PREFIX.length());
    return !rest.isEmpty() && !rest.contains(".");
  }

  @Override
  public SubscriptionDecision authorize(
      AuthenticatedUser user, String destination, String stompSessionId) {
    UUID roomId;
    try {
      roomId = UUID.fromString(destination.substring(ROOM_TOPIC_PREFIX.length()));
    } catch (IllegalArgumentException e) {
      return new SubscriptionDecision.SilentlyDrop();
    }
    if (!repository.isCurrentParticipant(roomId, user.userId())) {
      // IMP-GATE-51: a caller who lost the race to their own grace expiry is told plainly
      // (FR-REJOIN-007) rather than left to read silence as "room not found." Every other
      // non-member — a stranger, or an intentional past departure — stays a silent drop.
      if (repository.lostRaceToOwnExpiry(roomId, user.userId())) {
        return new SubscriptionDecision.RejectWithError(
            "SLOT_RELEASED",
            "Your reservation expired before you reconnected. Rejoin as a new participant.",
            0);
      }
      return new SubscriptionDecision.SilentlyDrop();
    }

    RoomOwnershipService.ClaimOutcome outcome = ownership.claimWithRetry(roomId);
    if (outcome instanceof RoomOwnershipService.ClaimOutcome.AlreadyMine
        || outcome instanceof RoomOwnershipService.ClaimOutcome.Adopted) {
      sessionsByRoom
          .computeIfAbsent(roomId, id -> ConcurrentHashMap.newKeySet())
          .add(stompSessionId);
      // room-lifecycle.md §1.1: connection_count is a refcount over a user's concurrent
      // connections (FR-GRACE-006), resolved once per session that subscribes to this room's
      // topic — the mirror of registerDisconnect's decrement, and (room-lifecycle.md §3.2,
      // IMP-M2-08) the reconnect resolution when a grace reservation is outstanding. Guarded so a
      // session re-subscribing to the same topic (a client retry, not a new tab) is not counted
      // twice.
      if (membershipBySession.put(stompSessionId, new RoomMembership(roomId, user.userId()))
          == null) {
        roomService.onConnect(roomId, user.userId());
      }
      return new SubscriptionDecision.Allow();
    }
    return new SubscriptionDecision.RejectWithError(
        "ROOM_UNAVAILABLE_RETRY",
        "The room's owning instance is not currently reachable. Retry shortly.",
        RETRY_AFTER_MS);
  }

  /**
   * {@code room-lifecycle.md} §1.5 layer 3/§1.6: self-eviction or a per-room ownership mismatch
   * severs exactly this room's sockets, marked instance-initiated by the {@code
   * ROOM_OWNERSHIP_LOST} code rather than left to look like a client-initiated disconnect.
   */
  @EventListener
  void onRoomOwnershipLost(RoomOwnershipLostEvent event) {
    Set<String> sessions = sessionsByRoom.remove(event.roomId());
    if (sessions == null) {
      return;
    }
    for (String stompSessionId : sessions) {
      // Removed here, synchronously, before the socket is actually severed — see the class
      // Javadoc: this is what makes the eventual SessionDisconnectEvent read as instance-initiated
      // rather than a player disconnect once it fires.
      membershipBySession.remove(stompSessionId);
      connectionRegistry.closeSession(
          stompSessionId,
          "ROOM_OWNERSHIP_LOST",
          "This room is no longer served by this instance: " + event.reason());
    }
  }

  /**
   * {@code room-lifecycle.md} §3.1 (`IMP-M2-07`): a session with no recorded room membership here
   * is either one that never subscribed to a room topic, or one {@link #onRoomOwnershipLost} has
   * already tagged instance-initiated — both are correctly a no-op. A session that still has an
   * entry is a genuine socket loss (FR-DC-001): every cause other than an instance-initiated close
   * reaches here with the entry intact, including a revoked or rate-limited connection, which are
   * real involuntary disconnects from the room's own perspective.
   */
  @EventListener
  void onSessionDisconnect(SessionDisconnectEvent event) {
    String stompSessionId = event.getSessionId();
    RoomMembership membership = membershipBySession.remove(stompSessionId);
    for (Set<String> roomSessions : sessionsByRoom.values()) {
      roomSessions.remove(stompSessionId);
    }
    if (membership != null) {
      roomService.onDisconnect(membership.roomId(), membership.userId());
    }
  }
}
