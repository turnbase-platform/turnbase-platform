package io.turnbase.platform.chat;

import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/**
 * {@code room-websocket.md} § Error Handling envelope, mirrored here for chat's own destination —
 * {@code room.RoomCommandErrorSender}'s equivalent, package-private to that feature.
 */
@Component
class ChatCommandErrorSender {

  private final SimpMessagingTemplate messagingTemplate;

  ChatCommandErrorSender(SimpMessagingTemplate messagingTemplate) {
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
}
