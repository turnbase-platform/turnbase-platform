package io.turnbase.platform.chat;

/**
 * {@code room-chat-websocket.md} § Validation and Error Codes — a send failure carrying one of that
 * section's {@code code} strings. Caught at the command dispatch site so the originating command's
 * {@code correlationId} can be echoed back on {@code /user/queue/errors}.
 */
class ChatCommandException extends RuntimeException {

  private final String code;
  private final String message;

  ChatCommandException(String code, String message) {
    super(message);
    this.code = code;
    this.message = message;
  }

  String code() {
    return code;
  }

  /** Unlike {@link #getMessage()} — {@code Throwable}'s own accessor is {@code @Nullable}. */
  String message() {
    return message;
  }
}
