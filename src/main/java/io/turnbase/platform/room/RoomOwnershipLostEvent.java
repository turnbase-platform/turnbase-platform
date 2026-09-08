package io.turnbase.platform.room;

import java.util.UUID;

/**
 * Published when this instance stops owning a room outside a graceful release — a whole-instance
 * self-eviction ({@code room-lifecycle.md} §1.5 layer 3) or a per-room ownership mismatch found on
 * the same re-check. No subscriber exists yet: {@code IMP-M2-02}'s STOMP session registry will
 * listen for this to sever sockets and mark the closures instance-initiated, the same inversion
 * already used for {@code session.SessionRevokedEvent} ({@code IMP-GATE-28}) — this item builds the
 * mechanism, not the socket layer that reacts to it.
 */
record RoomOwnershipLostEvent(UUID roomId, String reason) {}
