package io.turnbase.platform.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** {@code game-asset-serving.md} §4 — per-game and shared-origin template matching. */
class GameOriginMatcherTest {

  private final GameOriginMatcher perGame =
      new GameOriginMatcher(new GameFrontendProperties("http://{gameId}.games.localhost:8080"));

  private final GameOriginMatcher shared =
      new GameOriginMatcher(new GameFrontendProperties("http://games.localhost:8080"));

  @Test
  void matchesAPerGameOriginOnTheConfiguredSuffix() {
    assertThat(perGame.isGameHost("chess.games.localhost:8080")).isTrue();
    assertThat(perGame.isGameHost("CHESS.GAMES.LOCALHOST:8080")).isTrue();
  }

  @Test
  void rejectsThePlatformHostUnderPerGameConfiguration() {
    assertThat(perGame.isGameHost("turnbase.localhost:8080")).isFalse();
  }

  @Test
  void rejectsAHostMissingTheGameIdLabel() {
    assertThat(perGame.isGameHost("games.localhost:8080")).isFalse();
  }

  @Test
  void rejectsANullHost() {
    assertThat(perGame.isGameHost(null)).isFalse();
  }

  @Test
  void aSharedOriginTemplateMatchesOnlyItsExactHost() {
    assertThat(shared.isGameHost("games.localhost:8080")).isTrue();
    assertThat(shared.isGameHost("chess.games.localhost:8080")).isFalse();
    assertThat(shared.isGameHost("turnbase.localhost:8080")).isFalse();
  }
}
