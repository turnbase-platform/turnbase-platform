package io.turnbase.platform.room;

import io.turnbase.platform.game.StartPhase;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/**
 * {@code room-websocket.md} § {@code GAME_START_STATUS}: what {@code START_GAME} is doing, told to
 * everyone in the room on {@code /topic/room.{roomId}.start}. Ephemeral and unversioned on purpose
 * — it is not room state, so it neither takes a {@code room.version} nor appears in a snapshot, and
 * a client that misses one simply waits for the next or for {@code GAME_STARTED}.
 */
@Component
class RoomStartStatusPublisher {

  private final SimpMessagingTemplate messagingTemplate;

  RoomStartStatusPublisher(SimpMessagingTemplate messagingTemplate) {
    this.messagingTemplate = messagingTemplate;
  }

  /** {@code START_GAME} passed its preconditions and the session is being created. */
  void starting(UUID roomId) {
    publish(roomId, "STARTING", null);
  }

  void phase(UUID roomId, StartPhase phase) {
    publish(roomId, phase.name(), null);
  }

  void failed(UUID roomId, String reason) {
    publish(roomId, "FAILED", reason);
  }

  private void publish(UUID roomId, String phase, @Nullable String reason) {
    Map<String, Object> payload = new HashMap<>();
    payload.put("phase", phase);
    if (reason != null) {
      payload.put("reason", reason);
    }
    messagingTemplate.convertAndSend(
        "/topic/room." + roomId + ".start",
        (Object) Map.of("type", "GAME_START_STATUS", "payload", payload));
  }
}
