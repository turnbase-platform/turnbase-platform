package io.turnbase.platform.upload;

import static org.assertj.core.api.Assertions.assertThat;

import io.turnbase.bundle.VersionOrdering;
import org.junit.jupiter.api.Test;

/** {@code bundle-format.md} §6.1/§8: the MVP default range is {@code 0.3.0-0.3.0}. */
class GdkCompatibilityPropertiesTest {

  @Test
  void mvpDefaultRangeIsExactlyOneVersion() {
    var range = new GdkCompatibilityProperties("0.3.0-0.3.0").range();

    assertThat(range.contains(VersionOrdering.Version.parse("0.3.0"))).isTrue();
    assertThat(range.contains(VersionOrdering.Version.parse("0.2.9"))).isFalse();
    assertThat(range.contains(VersionOrdering.Version.parse("0.3.1"))).isFalse();
  }

  @Test
  void widerRangeContainsEverythingBetween() {
    var range = new GdkCompatibilityProperties("1.0.0-1.5.2").range();

    assertThat(range.contains(VersionOrdering.Version.parse("1.0.0"))).isTrue();
    assertThat(range.contains(VersionOrdering.Version.parse("1.3.9"))).isTrue();
    assertThat(range.contains(VersionOrdering.Version.parse("1.5.2"))).isTrue();
    assertThat(range.contains(VersionOrdering.Version.parse("1.5.3"))).isFalse();
    assertThat(range.contains(VersionOrdering.Version.parse("0.9.9"))).isFalse();
  }
}
