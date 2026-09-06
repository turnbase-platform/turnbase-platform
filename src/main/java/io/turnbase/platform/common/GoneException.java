package io.turnbase.platform.common;

/**
 * {@code error-handling.md} taxonomy: {@code gone}, 410 — a resource that existed but is
 * permanently unavailable (e.g. an expired upload slot).
 */
public class GoneException extends DomainException {

  public GoneException(String message) {
    super(ProblemType.GONE, message);
  }
}
