package io.turnbase.platform.room;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * {@code room-lifecycle.md} §4 {@code detachSession} — the seam between the sweep's own duties
 * (§3.3 step 4, §5.1 duty 2's closure half, both built by {@code IMP-M2-07}) and {@link
 * RoomSessionDetacherImpl}, the real mechanism {@code IMP-M2-09} builds (`IMP-GATE-49`).
 *
 * <p>Called after the caller's own transaction commits — {@code detachSession}'s own step 0
 * re-claims the room itself, so the caller holds no lock across this call.
 */
interface RoomSessionDetacher {

  void detach(UUID roomId, @Nullable UUID sessionId, String reason, String nextState);
}
