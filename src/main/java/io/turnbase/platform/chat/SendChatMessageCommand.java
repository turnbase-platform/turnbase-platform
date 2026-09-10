package io.turnbase.platform.chat;

import org.jspecify.annotations.Nullable;

/**
 * {@code room-chat-websocket.md} § Client→Server: {@code SEND_CHAT_MESSAGE} on {@code
 * /app/room.{roomId}.chat} — the destination's only command, unlike {@code room}'s member/host
 * destinations, so a typed record binds directly rather than dispatching on {@code type} from a raw
 * {@code JsonNode} tree.
 */
record SendChatMessageCommand(
    String type, @Nullable String correlationId, @Nullable ChatPayload payload) {

  record ChatPayload(@Nullable String channel, @Nullable String text) {}
}
