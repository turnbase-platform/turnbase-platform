package io.turnbase.platform.room;

import java.util.UUID;

/**
 * {@code room-lifecycle.md} §4 {@code terminateAndCleanup} — the seam between {@link
 * RoomSessionDetacherImpl}'s detach transaction, which has already recorded the durable {@code
 * session_teardown} to-do in the same transaction ({@code ADR-0010}), and the real Game Host {@code
 * TerminateSession} call and Redis key cleanup. Same shape as {@link GameSessionStarter}/ {@link
 * GameInputDispatcher}: no gRPC client to the Game Host exists anywhere in this repository yet, so
 * {@link SessionTerminatorImpl} always takes the pseudocode's failure branch and {@link
 * ReconciliationJob} duty (a) is what re-drives the resulting {@code session_teardown} debt.
 */
interface SessionTerminator {

  void terminateAndCleanup(UUID sessionId, String reason);
}
