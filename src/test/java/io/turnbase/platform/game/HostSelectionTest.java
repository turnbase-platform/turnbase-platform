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
    assertThat(HostRegistry.choose(List.of(cold, warm), CAP, 1)).contains("warm");
  }

  @Test
  void warmHostAtCapIsSkippedForACold() {
    var warm = new Candidate("warm", true, CAP);
    var cold = new Candidate("cold", false, 3);
    assertThat(HostRegistry.choose(List.of(warm, cold), CAP, 1)).contains("cold");
  }

  @Test
  void everyHostAtCapGivesNoHost() {
    var a = new Candidate("a", true, CAP);
    var b = new Candidate("b", false, CAP + 4);
    assertThat(HostRegistry.choose(List.of(a, b), CAP, 1)).isEqualTo(Optional.empty());
  }

  @Test
  void lowestActiveSessionsAmongEquals() {
    var a = new Candidate("a", true, 5);
    var b = new Candidate("b", true, 2);
    var c = new Candidate("c", true, 2);
    assertThat(HostRegistry.choose(List.of(a, c, b), CAP, 1)).contains("b");
  }

  @Test
  void noCandidatesGivesNoHost() {
    assertThat(HostRegistry.choose(List.of(), CAP, 1)).isEmpty();
  }

  @Test
  void aColdHostIsWarmedWhileFewerThanTheWantedHostsAreWarm() {
    var warm = new Candidate("warm", true, 3);
    var cold = new Candidate("cold", false, 0);
    assertThat(HostRegistry.choose(List.of(warm, cold), CAP, 2)).contains("cold");
  }

  @Test
  void onceEnoughHostsAreWarmTheEmptierWarmOneWins() {
    var a = new Candidate("a", true, 3);
    var b = new Candidate("b", true, 1);
    var cold = new Candidate("c", false, 0);
    assertThat(HostRegistry.choose(List.of(a, b, cold), CAP, 2)).contains("b");
  }

  @Test
  void noWarmHostYetFallsBackToLowestSessions() {
    var a = new Candidate("a", false, 2);
    var b = new Candidate("b", false, 1);
    assertThat(HostRegistry.choose(List.of(a, b), CAP, 2)).contains("b");
  }
}
