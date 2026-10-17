package io.turnbase.platform.game;

/**
 * Where a {@code START_GAME} is, while {@link GameSessionService#create} is still working on it.
 * Reported to {@link StartProgress} so the room can tell everyone in it; the terminal outcomes are
 * not phases — success is {@code GAME_STARTED}, failure is a {@code GameSessionCreationException}.
 */
public enum StartPhase {
  /** No eligible Game Host yet — one is starting, or the platform has just restarted. */
  WAITING_FOR_HOST,
  /** A host was chosen and is booting the game ({@code CreateSession} is in flight). */
  LOADING_GAME,
  /** The first host did not answer; trying again. */
  RETRYING
}
