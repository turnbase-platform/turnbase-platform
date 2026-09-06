package io.turnbase.platform.common;

import java.util.List;

/**
 * {@code error-handling.md} taxonomy: {@code validation-failed}, 400, raised outside Bean
 * Validation's own exception types — e.g. {@link SortFieldValidator} rejecting an unwhitelisted
 * sort key ({@code pagination-filtering-sorting.md} § Sorting § Implementation).
 */
public class ValidationFailedException extends DomainException {

  private final List<FieldValidationError> errors;

  public ValidationFailedException(String message, List<FieldValidationError> errors) {
    super(ProblemType.VALIDATION_FAILED, message);
    this.errors = List.copyOf(errors);
  }

  public List<FieldValidationError> errors() {
    return errors;
  }
}
