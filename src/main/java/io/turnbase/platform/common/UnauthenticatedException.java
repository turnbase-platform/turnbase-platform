package io.turnbase.platform.common;

/**
 * {@code error-handling.md} taxonomy: {@code unauthenticated}, 401 — missing, expired, or invalid
 * session token. {@code IMP-M1-03}'s session/JWT wiring throws this once authentication exists;
 * this item only owns the response shape.
 */
public class UnauthenticatedException extends DomainException {

  public UnauthenticatedException(String message) {
    super(ProblemType.UNAUTHENTICATED, message);
  }
}
