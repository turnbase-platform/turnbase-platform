package io.turnbase.platform.room;

/**
 * {@code data-model.md} §4.2's outbox drain (`room-lifecycle.md` §5.1 duty 4) — the seam between
 * this item's drain loop (allocate-once sequencing, {@code dispatched_at} stamped only on ACK) and
 * the real Game Host gRPC {@code SubmitInput} call, which needs the orchestration client {@code
 * IMP-M3-05} builds. Same shape as {@code GameSessionService}/{@link RoomSessionDetacher}: no gRPC
 * client to the Game Host exists anywhere in this repository yet.
 *
 * @return {@code true} on acknowledged delivery ({@code dispatched_at} may be stamped); {@code
 *     false} on any failure, which the caller records as an attempt and leaves for retry — never
 *     thrown, matching {@code terminateAndCleanup}'s own "never throw to the caller" rule.
 */
interface GameInputDispatcher {

  boolean dispatch(long outboxRowId, long sequence, String payloadJson);
}
