package io.turnbase.platform.room;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * {@code room-lifecycle.md} §4 {@code detachSession} — the seam between this item's sweep duties
 * (§3.3 step 4, §5.1 duty 2's closure half) and the real mechanism {@code IMP-M2-09} builds
 * (`IMP-GATE-49`), matching {@link GameSessionStarter}'s identical relationship to {@code
 * IMP-M3-05}: {@code IMP-M2-09} depends on this item, so §4's owner cannot exist yet when these
 * call sites are written.
 *
 * <p>Called after this item's own transaction commits — {@code detachSession}'s own step 0
 * re-claims the room itself, so the caller holds no lock across this call.
 */
interface RoomSessionDetacher {

  void detach(UUID roomId, @Nullable UUID sessionId, String reason, String nextState);
}
