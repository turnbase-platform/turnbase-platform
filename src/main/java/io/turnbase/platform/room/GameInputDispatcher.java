package io.turnbase.platform.room;

import java.util.UUID;

/**
 * {@code data-model.md} §4.2's outbox drain (`room-lifecycle.md` §5.1 duty 4) — the seam between
 * this item's drain loop (allocate-once sequencing, {@code dispatched_at} stamped only on ACK) and
 * the Game Host's {@code ProcessInput}.
 */
interface GameInputDispatcher {

  /** A fresh per-session sequence, allocated once per outbox row and then reused on every retry. */
  long allocateSequence(UUID sessionId);

  /**
   * @return {@code true} when the row needs no further attempt ({@code dispatched_at} may be
   *     stamped): delivered, rejected by the game, or the session already retired from its room;
   *     {@code false} on any failure, which the caller records as an attempt and leaves for retry —
   *     never thrown, matching {@code terminateAndCleanup}'s own "never throw to the caller" rule.
   */
  boolean dispatch(long outboxRowId, UUID sessionId, long sequence, String payloadJson);
}
