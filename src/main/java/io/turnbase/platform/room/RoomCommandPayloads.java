package io.turnbase.platform.room;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import java.util.UUID;

/**
 * {@code room-websocket.md} § Client→Server Command Catalogue — required-field extraction from a
 * command's {@code payload} tree, failing {@code VALIDATION_FAILED} (input-validation.md's field
 * naming) rather than silently defaulting a missing or malformed value.
 */
final class RoomCommandPayloads {

  private RoomCommandPayloads() {}

  static boolean requireBoolean(JsonNode payload, String field) {
    JsonNode node = payload.path(field);
    if (!node.isBoolean()) {
      throw validationFailed(field);
    }
    return node.asBoolean();
  }

  static String requireString(JsonNode payload, String field) {
    JsonNode node = payload.path(field);
    if (!node.isTextual() || node.asText().isBlank()) {
      throw validationFailed(field);
    }
    return node.asText();
  }

  static int requireInt(JsonNode payload, String field) {
    JsonNode node = payload.path(field);
    if (!node.isInt()) {
      throw validationFailed(field);
    }
    return node.asInt();
  }

  static UUID requireUuid(JsonNode payload, String field) {
    JsonNode node = payload.path(field);
    if (!node.isTextual()) {
      throw validationFailed(field);
    }
    try {
      return UUID.fromString(node.asText());
    } catch (IllegalArgumentException e) {
      throw validationFailed(field);
    }
  }

  private static RoomCommandException validationFailed(String field) {
    return new RoomCommandException(
        "VALIDATION_FAILED", "Missing or malformed field.", Map.of("field", field));
  }
}
