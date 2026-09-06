package io.turnbase.platform.common;

/**
 * {@code error-handling.md} taxonomy: {@code forbidden}, 403 — authenticated but insufficient
 * permission. Ownership predicates ({@code rest-conventions.md} § Authorization and Ownership)
 * throw this from each feature's own handler, under the same transaction as the mutation.
 */
public class ForbiddenException extends DomainException {

  public ForbiddenException(String message) {
    super(ProblemType.FORBIDDEN, message);
  }
}
