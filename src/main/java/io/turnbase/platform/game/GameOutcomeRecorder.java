package io.turnbase.platform.game;

/**
 * The seam from orchestration to history: a session ended with {@code game_over}, so its outcome is
 * to be recorded ({@code flows/06-game-end-and-rematch.md} §C). Implemented by {@code history},
 * which owns the tables; must be idempotent, since a replayed sequence may announce the same end
 * again.
 */
public interface GameOutcomeRecorder {

  void record(GameOutcomeReport report);
}
