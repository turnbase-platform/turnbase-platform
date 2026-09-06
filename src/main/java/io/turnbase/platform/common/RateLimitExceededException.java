package io.turnbase.platform.common;

/**
 * {@code error-handling.md} taxonomy: {@code rate-limit-exceeded}, 429. {@code IMP-M1-02}'s tiered
 * limiter throws this; this item only owns the response shape and the {@code Retry-After} header.
 */
public class RateLimitExceededException extends DomainException {

  private final long retryAfterSeconds;

  public RateLimitExceededException(String message, long retryAfterSeconds) {
    super(ProblemType.RATE_LIMIT_EXCEEDED, message);
    this.retryAfterSeconds = retryAfterSeconds;
  }

  public long retryAfterSeconds() {
    return retryAfterSeconds;
  }
}
