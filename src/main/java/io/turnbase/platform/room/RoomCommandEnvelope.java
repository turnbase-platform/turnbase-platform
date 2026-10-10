package io.turnbase.platform.room;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code room-websocket.md} § Message Envelope — the client→server command frame on {@code
 * /app/room.{roomId}.member} and {@code /app/room.{roomId}.host}. {@code type} dispatches within
 * the destination's single handler method, since STOMP routes by destination, not by a field inside
 * the body. {@code payload} is left as a raw tree because each command's fields differ and commands
 * with no required payload may omit the field entirely (hence {@code @Nullable}).
 */
record RoomCommandEnvelope(
    String type, @Nullable String correlationId, @Nullable JsonNode payload) {

  JsonNode payloadOrEmpty(JsonMapper objectMapper) {
    return payload != null ? payload : objectMapper.createObjectNode();
  }
}
