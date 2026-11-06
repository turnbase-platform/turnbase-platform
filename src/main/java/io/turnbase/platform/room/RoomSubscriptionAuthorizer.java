package io.turnbase.platform.room;

import io.turnbase.platform.common.AuthenticatedUser;
import io.turnbase.platform.common.LifecycleLog;
import io.turnbase.platform.websocket.SubscriptionAuthorizer;
import io.turnbase.platform.websocket.SubscriptionDecision;
import io.turnbase.platform.websocket.WebSocketConnectionRegistry;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

  private static final Logger log = LoggerFactory.getLogger(RoomSubscriptionAuthorizer.class);

  private static final String ROOM_TOPIC_PREFIX = "/topic/room.";

  /** Matches {@code room-rest-api.md} §7's REST value — same protocol, same retry budget. */
  private static final long RETRY_AFTER_MS = 2000;

  private final RoomPresenceRepository presence;
  private final RoomRepository rooms;
  private final RoomOwnershipService ownership;
  private final WebSocketConnectionRegistry connectionRegistry;
  private final RoomService roomService;
  private final Map<UUID, Set<String>> sessionsByRoom = new ConcurrentHashMap<>();
  private final Map<String, RoomMembership> membershipBySession = new ConcurrentHashMap<>();

  /**
   * The inverse of {@link #sessionsByRoom}, so a disconnect touches the rooms its session is in and
   * not every room this instance has ever served (IMP-M16-13: the walk was 2.3% of platform CPU at
   * 64 pairs and grew with the rooms created since the start).
   */
  private final Map<String, Set<UUID>> roomsBySession = new ConcurrentHashMap<>();

  RoomSubscriptionAuthorizer(
      RoomPresenceRepository presence,
      RoomRepository rooms,
      RoomOwnershipService ownership,
      WebSocketConnectionRegistry connectionRegistry,
      RoomService roomService) {
    this.presence = presence;
    this.rooms = rooms;
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
    if (!rooms.isCurrentParticipant(roomId, user.userId())) {
      // IMP-GATE-51: a caller who lost the race to their own grace expiry is told plainly
      // (FR-REJOIN-007) rather than left to read silence as "room not found." Every other
      // non-member — a stranger, or an intentional past departure — stays a silent drop.
      if (presence.lostRaceToOwnExpiry(roomId, user.userId())) {
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
      sessionsByRoom.compute(
          roomId,
          (id, sessions) -> {
            Set<String> tracked = sessions == null ? ConcurrentHashMap.newKeySet() : sessions;
            tracked.add(stompSessionId);
            return tracked;
          });
      roomsBySession
          .computeIfAbsent(stompSessionId, id -> ConcurrentHashMap.newKeySet())
          .add(roomId);
      // room-lifecycle.md §1.1: connection_count is a refcount over a user's concurrent
      // connections (FR-GRACE-006), resolved once per session that subscribes to this room's
      // topic — the mirror of registerDisconnect's decrement, and (room-lifecycle.md §3.2,
      // IMP-M2-08) the reconnect resolution when a grace reservation is outstanding. Guarded so a
      // session re-subscribing to the same topic (a client retry, not a new tab) is not counted
      // twice.
      if (membershipBySession.put(stompSessionId, new RoomMembership(roomId, user.userId()))
          == null) {
        LifecycleLog.info(log, "Room socket connected", "roomId", roomId, "userId", user.userId());
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
      forgetRoom(stompSessionId, event.roomId());
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
   * {@code room-lifecycle.md} §1.6 step 4: the drain tells every client attached to this room to
   * reconnect. Tagged instance-initiated exactly as {@link #onRoomOwnershipLost} does it.
   */
  @EventListener
  void onRoomDraining(RoomDrainingEvent event) {
    Set<String> sessions = sessionsByRoom.remove(event.roomId());
    if (sessions == null) {
      return;
    }
    for (String stompSessionId : sessions) {
      forgetRoom(stompSessionId, event.roomId());
      membershipBySession.remove(stompSessionId);
      connectionRegistry.closeSession(
          stompSessionId,
          "RECONNECT_REQUIRED",
          "This instance is shutting down. Reconnect and rejoin the room.");
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
    Set<UUID> rooms = roomsBySession.remove(stompSessionId);
    if (rooms != null) {
      rooms.forEach(roomId -> forgetSession(roomId, stompSessionId));
    }
    if (membership != null) {
      LifecycleLog.info(
          log,
          "Room socket disconnected",
          "roomId",
          membership.roomId(),
          "userId",
          membership.userId());
      roomService.onDisconnect(membership.roomId(), membership.userId());
    }
  }

  /** Drops the session from the room's set and the room itself once its last session is gone. */
  private void forgetSession(UUID roomId, String stompSessionId) {
    sessionsByRoom.computeIfPresent(
        roomId,
        (id, sessions) -> {
          sessions.remove(stompSessionId);
          return sessions.isEmpty() ? null : sessions;
        });
  }

  private void forgetRoom(String stompSessionId, UUID roomId) {
    roomsBySession.computeIfPresent(
        stompSessionId,
        (id, rooms) -> {
          rooms.remove(roomId);
          return rooms.isEmpty() ? null : rooms;
        });
  }

  /** Rooms and sessions currently tracked, for the tests that pin the bookkeeping to its size. */
  int trackedRooms() {
    return sessionsByRoom.size();
  }

  int trackedSessions() {
    return roomsBySession.size();
  }
}
