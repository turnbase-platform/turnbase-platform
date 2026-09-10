package io.turnbase.platform.room;

import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/**
 * {@code room-websocket.md} § Error Handling — the {@code /user/queue/errors} envelope shared by
 * the member and host command controllers, echoing the originating command's {@code correlationId}.
 */
@Component
class RoomCommandErrorSender {

  private final SimpMessagingTemplate messagingTemplate;

  RoomCommandErrorSender(SimpMessagingTemplate messagingTemplate) {
    this.messagingTemplate = messagingTemplate;
  }

  void send(
      Principal principal,
      UUID roomId,
      @Nullable String correlationId,
      String code,
      String message) {
    send(principal, roomId, correlationId, code, message, Map.of());
  }

  void send(
      Principal principal,
      UUID roomId,
      @Nullable String correlationId,
      String code,
      String message,
      Map<String, Object> extra) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("correlationId", correlationId);
    payload.put("code", code);
    payload.put("message", message);
    payload.put("roomId", roomId);
    payload.putAll(extra);
    messagingTemplate.convertAndSendToUser(
        principal.getName(), "/queue/errors", Map.of("type", "ERROR", "payload", payload));
  }

  /**
   * {@code room-websocket.md}'s {@code KICK_PLAYER} unicast — sent to the kicked player, not the
   * command's sender, and deliberately not {@link #send}'s shape: no {@code correlationId} key at
   * all (not even null), since this is not a response to a command the recipient sent.
   */
  void sendKicked(UUID targetUserId, UUID roomId) {
    Map<String, Object> payload =
        Map.of(
            "code", "KICKED",
            "message", "You were removed from the room by the host.",
            "roomId", roomId);
    messagingTemplate.convertAndSendToUser(
        targetUserId.toString(), "/queue/errors", Map.of("type", "ERROR", "payload", payload));
  }
}
