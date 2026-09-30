package io.turnbase.platform.history;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** {@code statistics-rest-api.md} §2. */
record StatisticsResponse(long totalPlayed, @Nullable MostPlayedGame mostPlayedGame) {

  record MostPlayedGame(UUID gameId, String title) {}

  static StatisticsResponse zeroState() {
    return new StatisticsResponse(0, null);
  }
}
