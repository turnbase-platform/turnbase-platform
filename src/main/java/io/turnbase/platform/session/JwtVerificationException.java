package io.turnbase.platform.session;

/**
 * A session token failed signature verification, named no key in the current set, or is malformed.
 * Mapping this to a 401 is the concern of whichever feature terminates a request (login/session
 * check land in {@code IMP-M1-04}); this exception is deliberately not a {@code DomainException} —
 * this item builds no HTTP endpoint for it to cross.
 */
public class JwtVerificationException extends RuntimeException {

  public JwtVerificationException(String message) {
    super(message);
  }

  public JwtVerificationException(String message, Throwable cause) {
    super(message, cause);
  }
}
