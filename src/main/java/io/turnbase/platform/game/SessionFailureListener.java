package io.turnbase.platform.game;

import java.util.UUID;

/**
 * The seam from the orchestration side to the room for a session that reached the retry limit
 * (FR-FAIL-003/005, {@code flows/07-room-closure.md} caller 4): clients are told {@code
 * SESSION_FAILED} and the room returns to Lobby, not closed. Implemented by the room, which owns
 * the tables it mutates; must be idempotent, since every failed attempt past the limit announces it
 * again.
 */
public interface SessionFailureListener {

  void onRetryLimitReached(UUID sessionId);
}
