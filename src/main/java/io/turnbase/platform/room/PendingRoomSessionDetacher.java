package io.turnbase.platform.room;

import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * The only {@link RoomSessionDetacher} until {@code IMP-M2-09} lands ({@code IMP-GATE-49}). A no-op
 * is the honest behaviour: no room can carry a real {@code active_session_id} before {@code
 * IMP-M3-05} either, so a room this stub leaves open has no live session pinning any resource yet.
 * This item's own sweep duties are proven by asserting the call happens under the right condition,
 * not by what it produces.
 */
@Component
class PendingRoomSessionDetacher implements RoomSessionDetacher {

  @Override
  public void detach(UUID roomId, @Nullable UUID sessionId, String reason, String nextState) {
    // IMP-M2-09 replaces this with detachSession's real implementation.
  }
}
