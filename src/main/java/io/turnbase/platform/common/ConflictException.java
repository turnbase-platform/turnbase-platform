package io.turnbase.platform.common;

/** {@code error-handling.md} taxonomy: {@code conflict}, 409 — unique constraint violations. */
public class ConflictException extends DomainException {

  public ConflictException(String message) {
    super(ProblemType.CONFLICT, message);
  }
}
