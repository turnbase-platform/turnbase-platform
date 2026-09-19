package io.turnbase.platform.upload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * {@code data-model-catalog.md} §2's worked example: {@code '1.10.0' < '1.9.0'} as text, but {@code
 * 1.10.0 > 1.9.0} as three integers — FR-GAME-009's own comparison.
 */
class VersionOrderingTest {

  @Test
  void comparesThreeIntegersNotText() {
    var v1_10_0 = VersionOrdering.Version.parse("1.10.0");
    var v1_9_0 = VersionOrdering.Version.parse("1.9.0");

    assertThat(VersionOrdering.compare(v1_10_0, v1_9_0)).isPositive();
    assertThat("1.10.0".compareTo("1.9.0")).isNegative(); // the text-ordering trap, for contrast
  }

  @Test
  void equalVersionsCompareToZero() {
    assertThat(
            VersionOrdering.compare(
                VersionOrdering.Version.parse("2.0.0"), VersionOrdering.Version.parse("2.0.0")))
        .isZero();
  }

  @Test
  void validFormatAccepted() {
    assertThat(VersionOrdering.isValidFormat("1.2.3")).isTrue();
    assertThat(VersionOrdering.isValidFormat("0.0.0")).isTrue();
  }

  @Test
  void invalidFormatsRejected() {
    assertThat(VersionOrdering.isValidFormat("1.2")).isFalse();
    assertThat(VersionOrdering.isValidFormat("1.2.3.4")).isFalse();
    assertThat(VersionOrdering.isValidFormat("v1.2.3")).isFalse();
    assertThat(VersionOrdering.isValidFormat("1.2.x")).isFalse();
    assertThat(VersionOrdering.isValidFormat("")).isFalse();
  }

  @Test
  void parseRejectsInvalidFormat() {
    assertThatThrownBy(() -> VersionOrdering.Version.parse("1.2"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
