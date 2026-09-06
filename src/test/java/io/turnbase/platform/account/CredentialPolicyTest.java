package io.turnbase.platform.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.turnbase.platform.common.ValidationFailedException;
import org.junit.jupiter.api.Test;

/**
 * input-validation.md § Field catalogue: {@code email} (FR-AUTH-002) and {@code password}
 * (FR-AUTH-005).
 */
class CredentialPolicyTest {

  private final CredentialPolicy policy = new CredentialPolicy();

  @Test
  void acceptsAnOrdinaryEmail() {
    assertThat(policy.validateEmail("alice@example.com")).isEqualTo("alice@example.com");
  }

  @Test
  void rejectsAnEmailWithNoAtSign() {
    assertThatThrownBy(() -> policy.validateEmail("alice.example.com"))
        .isInstanceOf(ValidationFailedException.class);
  }

  @Test
  void rejectsAnEmailWithTwoAtSigns() {
    assertThatThrownBy(() -> policy.validateEmail("al@ice@example.com"))
        .isInstanceOf(ValidationFailedException.class);
  }

  @Test
  void rejectsAnEmailWithAnEmptyLocalPart() {
    assertThatThrownBy(() -> policy.validateEmail("@example.com"))
        .isInstanceOf(ValidationFailedException.class);
  }

  @Test
  void rejectsAnEmailWhoseDomainHasNoDot() {
    assertThatThrownBy(() -> policy.validateEmail("alice@example"))
        .isInstanceOf(ValidationFailedException.class);
  }

  @Test
  void rejectsANonAsciiEmail() {
    assertThatThrownBy(() -> policy.validateEmail("аlice@example.com"))
        .isInstanceOf(ValidationFailedException.class);
  }

  @Test
  void acceptsAnEightCharacterPassword() {
    assertThat(policy.validatePassword("12345678")).isEqualTo("12345678");
  }

  @Test
  void rejectsAPasswordShorterThanEightCharacters() {
    assertThatThrownBy(() -> policy.validatePassword("1234567"))
        .isInstanceOf(ValidationFailedException.class);
  }

  @Test
  void rejectsAPasswordLongerThanOneHundredTwentyEightCharacters() {
    assertThatThrownBy(() -> policy.validatePassword("a".repeat(129)))
        .isInstanceOf(ValidationFailedException.class);
  }

  @Test
  void placesNoCompositionRuleOnAPasswordsCharacterSet() {
    assertThat(policy.validatePassword("aaaaaaaa")).isEqualTo("aaaaaaaa");
  }
}
