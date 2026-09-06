package io.turnbase.platform.common;

import java.util.List;
import java.util.Set;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

/**
 * {@code pagination-filtering-sorting.md} § Sorting § Implementation: consulted before the query
 * executes, so an unsupported {@code sort} field never reaches the repository layer.
 */
@Component
public class SortFieldValidator {

  public void validate(Sort sort, Set<String> allowedFields) {
    for (Sort.Order order : sort) {
      if (!allowedFields.contains(order.getProperty())) {
        throw new ValidationFailedException(
            "One or more fields failed validation.",
            List.of(
                new FieldValidationError(
                    "sort",
                    "Unsupported sort field '"
                        + order.getProperty()
                        + "'. Accepted fields: "
                        + allowedFields)));
      }
    }
  }
}
