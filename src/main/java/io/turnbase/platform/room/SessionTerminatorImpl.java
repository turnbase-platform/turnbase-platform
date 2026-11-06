package io.turnbase.platform.room;

import io.turnbase.platform.game.SessionTerminationClient;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * {@code room-lifecycle.md} §4's {@code terminateAndCleanup}: step (a) and the key-family cleanup
 * of step (b) are {@link SessionTerminationClient}'s; this class owns the {@code session_teardown}
 * row. On confirmation the row is deleted; on any failure it records the attempt (step (c)) and is
 * left for {@link ReconciliationJob} duty (a). Never throws.
 */
@Component
class SessionTerminatorImpl implements SessionTerminator {

  private static final Logger log = LoggerFactory.getLogger(SessionTerminatorImpl.class);

  private final RoomOutboxRepository repository;
  private final SessionTerminationClient client;

  SessionTerminatorImpl(RoomOutboxRepository repository, SessionTerminationClient client) {
    this.repository = repository;
    this.client = client;
  }

  @Override
  public void terminateAndCleanup(UUID sessionId, String reason) {
    if (client.terminate(sessionId, reason)) {
      repository.deleteSessionTeardown(sessionId);
      return;
    }
    log.warn("terminateAndCleanup: session {} ({}) not confirmed terminated", sessionId, reason);
    repository.recordSessionTeardownFailure(sessionId, "TerminateSession not confirmed");
  }
}
