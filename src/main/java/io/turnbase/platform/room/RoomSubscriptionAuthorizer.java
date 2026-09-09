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
 * <p>Also tracks which STOMP sessions are subscribed to which room, purely so {@link
 * #onRoomOwnershipLost} can sever exactly that room's sockets — {@code room.RoomOwnershipLostEvent}
 * carries no session ids, and this is the only place that knows the mapping.
 */
@Component
class RoomSubscriptionAuthorizer implements SubscriptionAuthorizer {

  private static final String ROOM_TOPIC_PREFIX = "/topic/room.";

  /** Matches {@code room-rest-api.md} §7's REST value — same protocol, same retry budget. */
  private static final long RETRY_AFTER_MS = 2000;

  private final RoomRepository repository;
  private final RoomOwnershipService ownership;
  private final WebSocketConnectionRegistry connectionRegistry;
  private final Map<UUID, Set<String>> sessionsByRoom = new ConcurrentHashMap<>();

  RoomSubscriptionAuthorizer(
      RoomRepository repository,
      RoomOwnershipService ownership,
      WebSocketConnectionRegistry connectionRegistry) {
    this.repository = repository;
    this.ownership = ownership;
    this.connectionRegistry = connectionRegistry;
  }

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
      return new SubscriptionDecision.SilentlyDrop();
    }

    RoomOwnershipService.ClaimOutcome outcome = ownership.claimWithRetry(roomId);
    if (outcome instanceof RoomOwnershipService.ClaimOutcome.AlreadyMine
        || outcome instanceof RoomOwnershipService.ClaimOutcome.Adopted) {
      sessionsByRoom
          .computeIfAbsent(roomId, id -> ConcurrentHashMap.newKeySet())
          .add(stompSessionId);
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
      connectionRegistry.closeSession(
          stompSessionId,
          "ROOM_OWNERSHIP_LOST",
          "This room is no longer served by this instance: " + event.reason());
    }
  }

  /** Bookkeeping only — an unbounded map would otherwise grow with every past connection. */
  @EventListener
  void onSessionDisconnect(SessionDisconnectEvent event) {
    String stompSessionId = event.getSessionId();
    for (Set<String> sessions : sessionsByRoom.values()) {
      sessions.remove(stompSessionId);
    }
  }
}
