package io.turnbase.platform;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

/**
 * ADR-0014: Spring Modulith is the enforcement mechanism for the feature boundaries, not a
 * convention anyone has to remember. This test passes trivially today — every feature package is
 * empty — and exists so the first violation fails a build instead of a review.
 */
class ModularityTest {

  @Test
  void verifiesModularStructure() {
    ApplicationModules.of(TurnBaseApplication.class).verify();
  }
}
