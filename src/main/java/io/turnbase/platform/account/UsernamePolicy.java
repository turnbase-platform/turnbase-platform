package io.turnbase.platform.account;

import com.ibm.icu.text.SpoofChecker;
import io.turnbase.platform.common.Canonicalizer;
import io.turnbase.platform.common.FieldValidationError;
import io.turnbase.platform.common.UsernameSkeleton;
import io.turnbase.platform.common.ValidationFailedException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * input-validation.md § Usernames — charset and length (FR-AUTH-014), the ICU4J {@link
 * SpoofChecker} single-script restriction (FR-AUTH-015), and the confusable-skeleton uniqueness key
 * {@code data-model-accounts.md} §1 stores (FR-AUTH-003). The {@code guest-} reserved-prefix check
 * (FR-AUTH-013) also runs on the skeleton, per the same section — a lookalike prefix built from
 * confusable characters must be caught, not just the literal string.
 */
@Component
class UsernamePolicy {

  private static final int MIN_LENGTH = 3;
  private static final int MAX_LENGTH = 32;
  private static final String RESERVED_PREFIX = "guest-";

  /**
   * {@code L*}, {@code M*}, {@code Nd}, and the two ASCII punctuation characters the field allows.
   */
  private static final Pattern ALLOWED_CHARSET = Pattern.compile("^[\\p{L}\\p{M}\\p{Nd}_-]+$");

  private final SpoofChecker spoofChecker;
  private final String reservedPrefixSkeleton;

  UsernamePolicy() {
    this.spoofChecker =
        new SpoofChecker.Builder()
            .setRestrictionLevel(SpoofChecker.RestrictionLevel.MODERATELY_RESTRICTIVE)
            .setChecks(SpoofChecker.RESTRICTION_LEVEL)
            .build();
    this.reservedPrefixSkeleton = skeletonOf(RESERVED_PREFIX);
  }

  /**
   * Canonicalizes and validates a submitted username, returning its display form and uniqueness
   * skeleton.
   *
   * @throws ValidationFailedException charset, length, script restriction, or the reserved {@code
   *     guest-} prefix is violated — see the class note on why the prefix is a 400 rather than
   *     folded into the 409 duplicate-username response {@link ConflictException} covers ({@code
   *     data-model-accounts.md} §1's registration flow raises that separately, from the database's
   *     own unique-index violation)
   */
  ValidatedUsername validate(String raw) {
    String canonical = Canonicalizer.canonicalize(raw);
    List<FieldValidationError> errors = new ArrayList<>();

    if (Canonicalizer.hasForbiddenCodePoint(canonical)) {
      errors.add(new FieldValidationError("username", "contains a disallowed character"));
    } else {
      int length = canonical.codePointCount(0, canonical.length());
      if (length < MIN_LENGTH || length > MAX_LENGTH) {
        errors.add(new FieldValidationError("username", "must be 3-32 characters"));
      }
      if (!ALLOWED_CHARSET.matcher(canonical).matches()) {
        errors.add(new FieldValidationError("username", "contains a disallowed character"));
      } else if (!isLetterOrDigit(canonical.codePointAt(0))) {
        errors.add(new FieldValidationError("username", "must begin with a letter or digit"));
      }
      if (spoofChecker.failsChecks(canonical)) {
        errors.add(new FieldValidationError("username", "must use a single script"));
      }
    }

    if (!errors.isEmpty()) {
      throw new ValidationFailedException("Username failed validation.", errors);
    }

    String skeleton = skeletonOf(canonical);
    if (skeleton.startsWith(reservedPrefixSkeleton)) {
      errors.add(
          new FieldValidationError("username", "may not begin with the reserved prefix 'guest-'"));
      throw new ValidationFailedException("Username failed validation.", errors);
    }

    return new ValidatedUsername(canonical, skeleton);
  }

  /**
   * The skeleton of an already-canonicalized login identifier, for the username-or-email lookup
   * (FR-AUTH-006) — no charset/length/prefix validation, since an unrecognized identifier must fail
   * as "no such account", not as a 400.
   */
  String skeletonOfLoginIdentifier(String canonicalizedIdentifier) {
    return skeletonOf(canonicalizedIdentifier);
  }

  /**
   * The skeleton of a system-generated {@code guest-<serial>} username. {@link #validate} would
   * reject this string outright — the reserved-prefix check exists to stop a *registering* user
   * from claiming the prefix (FR-AUTH-013), not to stop the system from minting it.
   */
  String skeletonOfGuestUsername(String guestUsername) {
    return skeletonOf(guestUsername);
  }

  private String skeletonOf(String canonical) {
    return UsernameSkeleton.of(canonical);
  }

  private static boolean isLetterOrDigit(int codePoint) {
    return Character.isLetter(codePoint) || Character.isDigit(codePoint);
  }

  record ValidatedUsername(String display, String skeleton) {}
}
