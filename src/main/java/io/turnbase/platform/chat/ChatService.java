package io.turnbase.platform.chat;

import io.turnbase.platform.common.Canonicalizer;
import io.turnbase.platform.room.RoomChatPort;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

/**
 * {@code room-chat-websocket.md} § `SEND_CHAT_MESSAGE` / § `CHAT_MESSAGE`. Chat frames carry {@code
 * version: null} and are published straight to {@code /topic/room.{roomId}.chat} or {@code
 * /topic/room.{roomId}.team.{teamId}} — never through {@code room.RoomEventExecutor#execute}, which
 * only ever bumps the room version and publishes to the bare room topic (IR-08: two writers on one
 * counter is the mistake this avoids).
 */
@Service
class ChatService {

  /** {@code input-validation.md} field catalogue: chat {@code text} is 1–500 code points. */
  private static final int MAX_TEXT_CODE_POINTS = 500;

  private final RoomChatPort roomChatPort;
  private final SimpMessagingTemplate messagingTemplate;

  ChatService(RoomChatPort roomChatPort, SimpMessagingTemplate messagingTemplate) {
    this.roomChatPort = roomChatPort;
    this.messagingTemplate = messagingTemplate;
  }

  void send(UUID roomId, UUID userId, SendChatMessageCommand.@Nullable ChatPayload payload) {
    ChatChannel channel = parseChannel(payload);
    String text = canonicalizeText(payload);

    RoomChatPort.ChatSenderContext sender =
        roomChatPort
            .resolveSenderForSend(roomId, userId)
            .orElseThrow(
                () -> new ChatCommandException("NOT_MEMBER", "You are not a member of this room."));
    if (!"PLAYER".equals(sender.role())) {
      throw new ChatCommandException(
          "SPECTATOR_CANNOT_CHAT", "Spectators cannot send chat messages.");
    }

    String destination;
    UUID teamId = null;
    if (channel == ChatChannel.TEAM) {
      if (!sender.teamChatAvailable()) {
        throw new ChatCommandException(
            "TEAM_CHAT_NOT_AVAILABLE", "This game does not support team chat.");
      }
      teamId = sender.teamId();
      if (teamId == null) {
        throw new ChatCommandException("NO_TEAM_ASSIGNMENT", "You are not assigned to a team.");
      }
      destination = "/topic/room." + roomId + ".team." + teamId;
    } else {
      destination = "/topic/room." + roomId + ".chat";
    }

    Map<String, Object> chatPayload = new LinkedHashMap<>();
    chatPayload.put("messageId", UUID.randomUUID().toString());
    chatPayload.put("channel", channel.name());
    chatPayload.put("teamId", teamId == null ? null : teamId.toString());
    chatPayload.put("authorId", userId.toString());
    chatPayload.put("authorUsername", sender.username());
    chatPayload.put("text", text);
    chatPayload.put("serverTimestamp", sender.serverTimestamp().toString());

    Map<String, Object> envelope = new LinkedHashMap<>();
    envelope.put("type", "CHAT_MESSAGE");
    envelope.put("version", null);
    envelope.put("payload", chatPayload);
    messagingTemplate.convertAndSend(destination, (Object) envelope);
  }

  private ChatChannel parseChannel(SendChatMessageCommand.@Nullable ChatPayload payload) {
    String raw = payload == null ? null : payload.channel();
    if ("ROOM".equals(raw)) {
      return ChatChannel.ROOM;
    }
    if ("TEAM".equals(raw)) {
      return ChatChannel.TEAM;
    }
    throw new ChatCommandException("VALIDATION_FAILED", "channel must be ROOM or TEAM.");
  }

  /**
   * {@code input-validation.md} § Canonicalization, then the chat-specific "no control chars at
   * all".
   */
  private String canonicalizeText(SendChatMessageCommand.@Nullable ChatPayload payload) {
    String raw = payload == null ? null : payload.text();
    if (raw == null) {
      throw new ChatCommandException("MESSAGE_EMPTY", "text must not be empty.");
    }
    String canonical = Canonicalizer.canonicalize(raw);
    if (canonical.isEmpty()) {
      throw new ChatCommandException("MESSAGE_EMPTY", "text must not be empty.");
    }
    if (Canonicalizer.hasForbiddenCodePoint(canonical)) {
      throw new ChatCommandException("VALIDATION_FAILED", "text contains a disallowed character.");
    }
    if (canonical.codePoints().count() > MAX_TEXT_CODE_POINTS) {
      throw new ChatCommandException(
          "MESSAGE_TOO_LONG", "text must be at most " + MAX_TEXT_CODE_POINTS + " characters.");
    }
    return canonical;
  }
}
