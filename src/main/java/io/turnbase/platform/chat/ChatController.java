package io.turnbase.platform.chat;

import io.turnbase.platform.common.AuthenticatedUser;
import io.turnbase.platform.common.RoomUnavailableRetryException;
import java.security.Principal;
import java.util.Map;
import java.util.UUID;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Controller;

/**
 * {@code room-chat-websocket.md} § Client→Server: {@code SEND_CHAT_MESSAGE} on {@code
 * /app/room.{roomId}.chat}. Mirrors {@code room}'s member/host command controllers' dispatch shape,
 * kept in this package per ADR-0014's explicit {@code chat/} feature.
 */
@Controller
class ChatController {

  private final ChatService chatService;
  private final ChatCommandErrorSender errors;

  ChatController(ChatService chatService, ChatCommandErrorSender errors) {
    this.chatService = chatService;
    this.errors = errors;
  }

  @MessageMapping("room.{roomId}.chat")
  void handle(
      @DestinationVariable UUID roomId,
      @Payload SendChatMessageCommand command,
      Principal principal) {
    UUID userId = userIdOf(principal);
    try {
      dispatch(roomId, command, userId);
    } catch (ChatCommandException e) {
      errors.send(principal, roomId, command.correlationId(), e.code(), e.message());
    } catch (RoomUnavailableRetryException e) {
      errors.send(
          principal,
          roomId,
          command.correlationId(),
          "ROOM_UNAVAILABLE_RETRY",
          e.detail(),
          Map.of("retryAfterMs", e.retryAfterMs()));
    }
  }

  private void dispatch(UUID roomId, SendChatMessageCommand command, UUID userId) {
    if (!"SEND_CHAT_MESSAGE".equals(command.type())) {
      throw new ChatCommandException(
          "UNKNOWN_COMMAND", "Unrecognised command type: " + command.type());
    }
    chatService.send(roomId, userId, command.payload());
  }

  private static UUID userIdOf(Principal principal) {
    if (principal instanceof AuthenticatedUser user) {
      return user.userId();
    }
    throw new IllegalStateException(
        "a STOMP command reached the handler with no AuthenticatedUser principal — the CONNECT"
            + " handshake must have set one");
  }
}
