package io.turnbase.platform.common;

/**
 * {@code error-handling.md} taxonomy: {@code service-unavailable}, 503 — a transient upstream
 * failure (e.g. object storage unreachable during bundle confirm). Distinct from {@link
 * RoomUnavailableRetryException}, which carries its own {@code type} and a {@code retryAfterMs}
 * member for the room-join retry contract specifically.
 */
public class ServiceUnavailableException extends DomainException {

  public ServiceUnavailableException(String message) {
    super(ProblemType.SERVICE_UNAVAILABLE, message);
  }
}
