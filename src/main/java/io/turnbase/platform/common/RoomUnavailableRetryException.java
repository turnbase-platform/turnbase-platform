package io.turnbase.platform.common;

/**
 * {@code error-handling.md} taxonomy: {@code room-unavailable-retry}, 503 — the room's owning
 * instance could not be reached; the caller must back off and re-run the join flow. The REST half
 * of the one condition {@code room-rest-api.md} §7 and {@code room-websocket.md}'s {@code
 * ROOM_UNAVAILABLE_RETRY} STOMP code both name, deliberately matched.
 */
public class RoomUnavailableRetryException extends DomainException {

  private final long retryAfterMs;

  public RoomUnavailableRetryException(String message, long retryAfterMs) {
    super(ProblemType.ROOM_UNAVAILABLE_RETRY, message);
    this.retryAfterMs = retryAfterMs;
  }

  public long retryAfterMs() {
    return retryAfterMs;
  }
}
