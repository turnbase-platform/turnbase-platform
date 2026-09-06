package io.turnbase.platform.account;

import io.turnbase.platform.common.Canonicalizer;
import io.turnbase.platform.common.FieldValidationError;
import io.turnbase.platform.common.ValidationFailedException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * input-validation.md § Field catalogue — {@code email} (FR-AUTH-002, ASCII-only, ≤254/≤64) and
 * {@code password} (FR-AUTH-005, 8–128 code points, no composition rules).
 */
@Component
class CredentialPolicy {

  private static final int EMAIL_MAX_LENGTH = 254;
  private static final int EMAIL_LOCAL_MAX_LENGTH = 64;
  private static final int PASSWORD_MIN_LENGTH = 8;
  private static final int PASSWORD_MAX_LENGTH = 128;

  /**
   * @throws ValidationFailedException the address is not ASCII, exceeds the length caps, or fails
   *     FR-AUTH-002's shape (exactly one {@code @}, non-empty local part, a domain containing at
   *     least one {@code .})
   */
  String validateEmail(String raw) {
    String canonical = Canonicalizer.canonicalize(raw);
    List<FieldValidationError> errors = new ArrayList<>();

    if (Canonicalizer.hasForbiddenCodePoint(canonical) || !isAscii(canonical)) {
      errors.add(new FieldValidationError("email", "must be ASCII with no control characters"));
    } else {
      if (canonical.isEmpty() || canonical.length() > EMAIL_MAX_LENGTH) {
        errors.add(new FieldValidationError("email", "must be 1-254 characters"));
      }
      int at = canonical.indexOf('@');
      if (at <= 0 || canonical.indexOf('@', at + 1) != -1) {
        errors.add(
            new FieldValidationError(
                "email", "must contain exactly one '@' with a non-empty local part"));
      } else {
        String local = canonical.substring(0, at);
        String domain = canonical.substring(at + 1);
        if (local.length() > EMAIL_LOCAL_MAX_LENGTH) {
          errors.add(new FieldValidationError("email", "local part must be at most 64 characters"));
        }
        if (domain.isEmpty() || !domain.contains(".")) {
          errors.add(new FieldValidationError("email", "domain must contain at least one '.'"));
        }
      }
    }

    if (!errors.isEmpty()) {
      throw new ValidationFailedException("Email failed validation.", errors);
    }
    return canonical;
  }

  /**
   * @throws ValidationFailedException the password is not 8-128 code points, or carries a forbidden
   *     code point
   */
  String validatePassword(String raw) {
    String canonical = Canonicalizer.canonicalize(raw);
    List<FieldValidationError> errors = new ArrayList<>();

    if (Canonicalizer.hasForbiddenCodePoint(canonical)) {
      errors.add(new FieldValidationError("password", "contains a disallowed character"));
    } else {
      int length = canonical.codePointCount(0, canonical.length());
      if (length < PASSWORD_MIN_LENGTH || length > PASSWORD_MAX_LENGTH) {
        errors.add(new FieldValidationError("password", "must be 8-128 characters"));
      }
    }

    if (!errors.isEmpty()) {
      throw new ValidationFailedException("Password failed validation.", errors);
    }
    return canonical;
  }

  private static boolean isAscii(String s) {
    return s.chars().allMatch(c -> c < 0x80);
  }
}
