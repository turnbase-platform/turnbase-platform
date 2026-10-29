package io.turnbase.platform.game;

import static org.assertj.core.api.Assertions.assertThat;

import io.turnbase.platform.game.HostRegistry.Candidate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** {@code load-testing-phase-b.md} §5.1: the selection rule under a per-host session cap. */
class HostSelectionTest {

  private static final int CAP = 10;

  @Test
  void warmHostBelowCapIsPreferredOverAnEmptierColdOne() {
    var warm = new Candidate("warm", true, 9);
    var cold = new Candidate("cold", false, 0);
    assertThat(HostRegistry.choose(List.of(cold, warm), CAP)).contains("warm");
  }

  @Test
  void warmHostAtCapIsSkippedForACold() {
    var warm = new Candidate("warm", true, CAP);
    var cold = new Candidate("cold", false, 3);
    assertThat(HostRegistry.choose(List.of(warm, cold), CAP)).contains("cold");
  }

  @Test
  void everyHostAtCapGivesNoHost() {
    var a = new Candidate("a", true, CAP);
    var b = new Candidate("b", false, CAP + 4);
    assertThat(HostRegistry.choose(List.of(a, b), CAP)).isEqualTo(Optional.empty());
  }

  @Test
  void lowestActiveSessionsAmongEquals() {
    var a = new Candidate("a", true, 5);
    var b = new Candidate("b", true, 2);
    var c = new Candidate("c", true, 2);
    assertThat(HostRegistry.choose(List.of(a, c, b), CAP)).contains("b");
  }

  @Test
  void noCandidatesGivesNoHost() {
    assertThat(HostRegistry.choose(List.of(), CAP)).isEmpty();
  }
}
