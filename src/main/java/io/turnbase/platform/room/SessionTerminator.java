package io.turnbase.platform.room;

import java.util.UUID;

/**
 * {@code room-lifecycle.md} §4 {@code terminateAndCleanup} — the seam between {@link
 * RoomSessionDetacherImpl}'s detach transaction, which has already recorded the durable {@code
 * session_teardown} to-do in the same transaction ({@code ADR-0010}), and the Game Host {@code
 * TerminateSession} call with its Redis key cleanup. A failure leaves the row for {@link
 * ReconciliationJob} duty (a) to re-drive.
 */
interface SessionTerminator {

  void terminateAndCleanup(UUID sessionId, String reason);
}
