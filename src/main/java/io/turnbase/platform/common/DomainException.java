package io.turnbase.platform.common;

/**
 * Root of the domain exception hierarchy {@link GlobalExceptionHandler} maps to a {@link
 * ProblemType}. A new failure mode is a new subtype carrying its {@link ProblemType}, not a new
 * branch in the handler — {@code error-handling.md}'s taxonomy is closed, but the exceptions that
 * realize it are not.
 */
public abstract class DomainException extends RuntimeException {

  private final ProblemType problemType;
  private final String detail;

  protected DomainException(ProblemType problemType, String message) {
    super(message);
    this.problemType = problemType;
    this.detail = message;
  }

  public ProblemType problemType() {
    return problemType;
  }

  /**
   * The RFC 9457 {@code detail} text. Unlike {@link #getMessage()} — {@code Throwable}'s own
   * accessor is {@code @Nullable} — this is guaranteed non-null, since every constructor requires
   * one.
   */
  public String detail() {
    return detail;
  }
}
