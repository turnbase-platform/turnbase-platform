package io.turnbase.platform.room;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * {@code room-lifecycle.md} §4's {@code terminateAndCleanup} pseudocode, step (a): {@code
 * TerminateSession(sessionId, reason)} against the Game Host, deadline 2s. No gRPC client to the
 * Game Host exists anywhere in this repository yet (same gap {@code PendingGameInputDispatcher}
 * stands in for on the dispatch side), so step (a) always fails honestly rather than pretending
 * success — every call takes step (c): log WARN and record the attempt on the durable {@code
 * session_teardown} row, left for {@link ReconciliationJob} duty (a) to re-drive. Step (b), the
 * on-success cleanup, has no code here for the same reason {@code PendingGameInputDispatcher} has
 * none for its own success branch: it is never reached.
 */
@Component
class SessionTerminatorImpl implements SessionTerminator {

  private static final Logger log = LoggerFactory.getLogger(SessionTerminatorImpl.class);

  private final RoomRepository repository;

  SessionTerminatorImpl(RoomRepository repository) {
    this.repository = repository;
  }

  @Override
  public void terminateAndCleanup(UUID sessionId, String reason) {
    String error = "no Game Host gRPC client exists yet";
    log.warn(
        "terminateAndCleanup: session {} ({}) could not be confirmed terminated: {}",
        sessionId,
        reason,
        error);
    repository.recordSessionTeardownFailure(sessionId, error);
  }
}
