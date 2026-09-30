package io.turnbase.platform.game;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A session's first {@code game_over}, as the platform records it ({@code
 * flows/06-game-end-and-rematch.md} §B–C). Game code produced the outcome, so every id and number
 * in it is untrusted: ids stay strings and nothing here has been range-checked.
 *
 * @param roster the session's membership read <em>before</em> detach clears it, or {@code null}
 *     when no room claims the session any more
 */
public record GameOutcomeReport(
    UUID sessionId,
    UUID gameId,
    String gameVersion,
    Instant completedAt,
    boolean draw,
    List<TeamOutcome> teams,
    List<PlayerOutcome> players,
    @Nullable SessionRoster roster) {

  public record TeamOutcome(String teamId, int placement, Map<String, String> stats) {}

  public record PlayerOutcome(String playerId, Map<String, String> stats) {}
}
