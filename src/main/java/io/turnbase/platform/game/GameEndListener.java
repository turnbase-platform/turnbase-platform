package io.turnbase.platform.game;

import java.util.UUID;

/**
 * The seam from the orchestration side to the room: the game backend's first {@code game_over}
 * effect has been delivered, so the session must be detached and its room returned to Lobby ({@code
 * flows/06-game-end-and-rematch.md} §D). Implemented by the room, which owns the tables it mutates;
 * must be idempotent, since a replayed sequence may announce the same end again.
 */
public interface GameEndListener {

  void onGameOver(UUID sessionId);
}
