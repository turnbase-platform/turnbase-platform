package io.turnbase.platform.common;

/**
 * {@code error-handling.md} taxonomy: {@code payload-too-large}, 413 — the request body exceeds
 * {@code turnbase.http.max-body-size} ({@code input-validation.md} § Size limits).
 */
public class PayloadTooLargeException extends DomainException {

  public PayloadTooLargeException(String message) {
    super(ProblemType.PAYLOAD_TOO_LARGE, message);
  }
}
