package io.turnbase.platform.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.turnbase.platform.common.ValidationFailedException;
import org.junit.jupiter.api.Test;

/**
 * input-validation.md § Usernames: charset/length (FR-AUTH-014), single-script restriction
 * (FR-AUTH-015), the confusable skeleton (FR-AUTH-003), and the guest- reserved prefix
 * (FR-AUTH-013), checked on that same skeleton.
 */
class UsernamePolicyTest {

  private final UsernamePolicy policy = new UsernamePolicy();

  @Test
  void acceptsAnOrdinaryLatinUsername() {
    UsernamePolicy.ValidatedUsername result = policy.validate("Alice");

    assertThat(result.display()).isEqualTo("Alice");
    assertThat(result.skeleton()).isNotBlank();
  }

  @Test
  void rejectsAUsernameShorterThanThreeCharacters() {
    assertThatThrownBy(() -> policy.validate("ab")).isInstanceOf(ValidationFailedException.class);
  }

  @Test
  void rejectsAUsernameLongerThanThirtyTwoCharacters() {
    assertThatThrownBy(() -> policy.validate("a".repeat(33)))
        .isInstanceOf(ValidationFailedException.class);
  }

  @Test
  void rejectsASpaceInTheUsername() {
    assertThatThrownBy(() -> policy.validate("al ice"))
        .isInstanceOf(ValidationFailedException.class);
  }

  @Test
  void rejectsAUsernameNotStartingWithALetterOrDigit() {
    assertThatThrownBy(() -> policy.validate("_alice"))
        .isInstanceOf(ValidationFailedException.class);
  }

  @Test
  void rejectsAMixedScriptUsername() {
    // Latin 'a' followed by Cyrillic letters — the mix FR-AUTH-015 exists to reject.
    assertThatThrownBy(() -> policy.validate("aлиса"))
        .isInstanceOf(ValidationFailedException.class);
  }

  @Test
  void caseVariantsOfTheSameUsernameShareOneSkeleton() {
    // data-model-accounts.md §1's worked example ("Alice, ALICE, A1ice... all map to one key")
    // needs a single-script pair to pass FR-AUTH-015's own script check first — a literal
    // Latin+Cyrillic mix (its illustrative "Аlice") is rejected by that check before uniqueness
    // ever runs, which is FR-AUTH-015 working as intended, not a case this method reaches.
    UsernamePolicy.ValidatedUsername lower = policy.validate("alice");
    UsernamePolicy.ValidatedUsername upper = policy.validate("ALICE");

    assertThat(upper.skeleton()).isEqualTo(lower.skeleton());
  }

  @Test
  void rejectsTheLiteralReservedGuestPrefix() {
    assertThatThrownBy(() -> policy.validate("guest-123"))
        .isInstanceOf(ValidationFailedException.class);
  }

  @Test
  void rejectsAConfusableLookalikeOfTheReservedGuestPrefix() {
    // Cyrillic е (U+0435) in place of Latin e — input-validation.md's own example of why the
    // prefix check runs on the skeleton rather than a literal startsWith.
    assertThatThrownBy(() -> policy.validate("guеst-123"))
        .isInstanceOf(ValidationFailedException.class);
  }

  @Test
  void skeletonOfLoginIdentifierNeedsNoValidation() {
    // An email-shaped identifier is not a legal username, but must still resolve to some
    // skeleton rather than throwing — a non-match falls through to "no such account" (401), not
    // a 400.
    String skeleton = policy.skeletonOfLoginIdentifier("someone@example.com");

    assertThat(skeleton).isNotBlank();
  }

  @Test
  void skeletonOfGuestUsernameNeedsNoValidationDespiteTheReservedPrefix() {
    // validate() rejects "guest-0" outright (rejectsTheLiteralReservedGuestPrefix above) — this
    // method exists precisely because the system, not a registrant, is the one minting it.
    String skeleton = policy.skeletonOfGuestUsername("guest-0");

    assertThat(skeleton).isNotBlank();
  }

  @Test
  void distinctGuestSerialsYieldDistinctSkeletons() {
    // data-model-accounts.md §1: digit-to-letter confusable folding is one-way, so two
    // digit-only guest usernames never collide on skeleton — the guarantee insertGuestUser's
    // assertion-failure branch relies on.
    String skeletonOne = policy.skeletonOfGuestUsername("guest-1");
    String skeletonEleven = policy.skeletonOfGuestUsername("guest-11");

    assertThat(skeletonOne).isNotEqualTo(skeletonEleven);
  }
}
