package io.turnbase.platform.room;

import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The only {@link SessionTerminator} until a Game Host gRPC client exists. A no-op is the honest
 * behaviour: the {@code session_teardown} row {@link RoomRepository#detachSession} already wrote in
 * the same transaction is the durable record of the debt, left for that item's own sweep
 * (`IMP-M2-10`) to discharge once a real client exists — matching {@code
 * PendingGameInputDispatcher}'s "never pretend delivery succeeded" precedent.
 */
@Component
class PendingSessionTerminator implements SessionTerminator {

  @Override
  public void terminateAndCleanup(UUID sessionId, String reason) {
    // A future item replaces this with a real TerminateSession call and Redis key cleanup.
  }
}
