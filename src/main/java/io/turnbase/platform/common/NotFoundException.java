package io.turnbase.platform.common;

/** {@code error-handling.md} taxonomy: {@code not-found}, 404. */
public class NotFoundException extends DomainException {

  public NotFoundException(String message) {
    super(ProblemType.NOT_FOUND, message);
  }
}
