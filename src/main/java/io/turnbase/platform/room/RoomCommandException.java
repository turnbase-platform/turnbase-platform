package io.turnbase.platform.room;

import java.util.Map;

/**
 * {@code room-websocket.md} § Error Handling — a command failure carrying one of that section's
 * closed {@code code} strings. Caught at the command dispatch site (not via
 * {@code @MessageExceptionHandler}) so the originating command's {@code correlationId} can be
 * echoed back on {@code /user/queue/errors}, which the exception itself does not carry.
 */
class RoomCommandException extends RuntimeException {

  private final String code;
  private final String message;
  private final Map<String, Object> details;

  RoomCommandException(String code, String message) {
    this(code, message, Map.of());
  }

  RoomCommandException(String code, String message, Map<String, Object> details) {
    super(message);
    this.code = code;
    this.message = message;
    this.details = details;
  }

  String code() {
    return code;
  }

  /** Unlike {@link #getMessage()} — {@code Throwable}'s own accessor is {@code @Nullable}. */
  String message() {
    return message;
  }

  Map<String, Object> details() {
    return details;
  }
}
