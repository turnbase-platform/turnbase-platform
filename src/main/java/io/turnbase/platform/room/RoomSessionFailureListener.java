package io.turnbase.platform.room;

import io.turnbase.platform.game.SessionFailureListener;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/**
 * The retry-limit caller of {@code detachSession} ({@code flows/07-room-closure.md} caller 4): the
 * session topic hears {@code SESSION_FAILED} ({@code websocket.md}), then the room fails back to
 * Lobby with no {@code closed_reason}; the detach itself broadcasts {@code GAME_ENDED}. No history
 * entry is written (CON-001) — there was no {@code game_over}. A session no room claims any more
 * was already detached, which is the idempotent no-op.
 */
@Component
class RoomSessionFailureListener implements SessionFailureListener {

  private final RoomRepository repository;
  private final RoomSessionDetacher detacher;
  private final SimpMessagingTemplate messagingTemplate;

  RoomSessionFailureListener(
      RoomRepository repository,
      RoomSessionDetacher detacher,
      SimpMessagingTemplate messagingTemplate) {
    this.repository = repository;
    this.detacher = detacher;
    this.messagingTemplate = messagingTemplate;
  }

  @Override
  public void onRetryLimitReached(UUID sessionId) {
    repository
        .roomOfActiveSession(sessionId)
        .ifPresent(
            roomId -> {
              Map<String, Object> payload = new LinkedHashMap<>();
              payload.put("reason", "RETRY_LIMIT");
              payload.put("message", "The game encountered an error and could not continue.");
              Map<String, Object> message = new LinkedHashMap<>();
              message.put("type", "SESSION_FAILED");
              message.put("sessionId", sessionId);
              message.put("payload", payload);
              messagingTemplate.convertAndSend("/topic/game." + sessionId, (Object) message);
              detacher.detach(roomId, sessionId, "RETRY_LIMIT", "LOBBY");
            });
  }
}
