package io.turnbase.platform.room;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * One attempt at delivering a {@code game_input_outbox} row ({@code data-model.md} §4.2): the
 * sweep's duty 4 calls it for every pending row, and {@link #onPlayerLeftQueued} calls it right
 * after the transaction that wrote a {@code player_left} row commits (IMP-M9-05, CA-05). Both paths
 * follow the same rules — the sequence is allocated once and reused, {@code dispatched_at} is
 * stamped on ACK only, and a failure records {@code attempts}/{@code last_error} and leaves the row
 * for the sweep.
 */
@Component
class GameInputOutbox {

  private static final Logger log = LoggerFactory.getLogger(GameInputOutbox.class);

  private final RoomOutboxRepository repository;
  private final GameInputDispatcher dispatcher;

  GameInputOutbox(RoomOutboxRepository repository, GameInputDispatcher dispatcher) {
    this.repository = repository;
    this.dispatcher = dispatcher;
  }

  /**
   * The inline attempt. Never throws: the row is already committed, so any failure here is the
   * sweep's to retry. A row the teardown discarded in the meantime is simply not found.
   */
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
  void onPlayerLeftQueued(RoomRecords.PlayerLeftQueued event) {
    try {
      for (RoomRecords.OutboxRow row : repository.pendingOutboxRows(event.sessionId())) {
        drainOne(row);
      }
    } catch (RuntimeException e) {
      log.warn(
          "inline outbox dispatch for sessionId={} failed; the sweep retries",
          event.sessionId(),
          e);
    }
  }

  void drainOne(RoomRecords.OutboxRow row) {
    // data-model.md §4.2: allocated once from the session's `seq:` counter, persisted, reused on
    // every retry.
    long sequence =
        row.sequence() != null
            ? row.sequence()
            : repository.allocateOutboxSequence(
                row.id(), dispatcher.allocateSequence(row.sessionId()));
    boolean delivered;
    try {
      delivered = dispatcher.dispatch(row.id(), row.sessionId(), sequence, row.payloadJson());
    } catch (RuntimeException e) {
      log.warn("outbox row {} dispatch threw", row.id(), e);
      repository.recordOutboxAttemptFailure(
          row.id(), e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
      return;
    }
    if (delivered) {
      repository.markOutboxDispatched(row.id());
    } else {
      repository.recordOutboxAttemptFailure(row.id(), "dispatch reported failure");
    }
  }
}
