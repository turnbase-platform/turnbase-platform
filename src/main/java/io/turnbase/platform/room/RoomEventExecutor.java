package io.turnbase.platform.room;

import io.turnbase.platform.common.LifecycleLog;
import io.turnbase.platform.common.RoomUnavailableRetryException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code room-lifecycle.md} §2.1 / {@code room-websocket.md} "Per-Room Serialization and
 * Versioning" turned into a real component: the one place every room mutation — REST or STOMP —
 * acquires the room's {@link ReentrantLock}, runs its transaction, assigns and fence-commits the
 * next {@code room.version}, and publishes the resulting delta to {@code /topic/room.{roomId}}, all
 * before the lock is released. {@code IMP-M2-01}'s REST join/leave are the first two callers
 * ({@code IMP-GATE-43}/{@code IMP-GATE-44}); {@code IMP-M2-05}/{@code M2-06}'s STOMP command
 * handlers are the next.
 *
 * <p>The commit is a fenced conditional update, {@code WHERE room_id = :id AND version = :expected
 * AND owner_epoch = :epoch} ({@code data-model.md} §1) — {@code :epoch} comes from {@link
 * RoomOwnershipService#epochOf}, resolved fresh on every call rather than threaded in by the
 * caller, so a STOMP command handler with no claim step of its own gets the same fencing a REST
 * caller that claimed earlier does.
 */
@Component
class RoomEventExecutor {

  /**
   * {@code room-lifecycle.md} §2.1: "set a lock-acquisition timeout... on expiry, fail the command
   * and publish nothing, but do not self-evict." No document supplies a value; this budget matches
   * {@code RoomOwnershipService}'s own retry budget (a few hundred milliseconds) times a safety
   * margin, not a measurement.
   */
  private static final long LOCK_TIMEOUT_MS = 2_000;

  /**
   * {@code room-lifecycle.md} §2.1's paired "statement timeout" — bounds how long one mutation can
   * hold the room lock so a stalled database degrades one room's throughput rather than exhausting
   * every inbound STOMP thread behind it. Also unspecified by any document; a reasoned default.
   */
  private static final int STATEMENT_TIMEOUT_SECONDS = 5;

  private static final long RETRY_AFTER_MS = 2000;

  private static final Logger log = LoggerFactory.getLogger(RoomEventExecutor.class);

  private final Map<UUID, ReentrantLock> locks = new ConcurrentHashMap<>();
  private final RoomOwnershipService ownership;
  private final JdbcClient jdbcClient;
  private final SimpMessagingTemplate messagingTemplate;
  private final TransactionTemplate transactionTemplate;

  RoomEventExecutor(
      RoomOwnershipService ownership,
      JdbcClient jdbcClient,
      SimpMessagingTemplate messagingTemplate,
      PlatformTransactionManager transactionManager) {
    this.ownership = ownership;
    this.jdbcClient = jdbcClient;
    this.messagingTemplate = messagingTemplate;
    this.transactionTemplate = new TransactionTemplate(transactionManager);
    this.transactionTemplate.setTimeout(STATEMENT_TIMEOUT_SECONDS);
  }

  /**
   * Lock outer → transaction inner → publish after the transaction returns (already committed),
   * before the lock in the {@code finally} below is released — the nesting {@code
   * room-websocket.md} calls out as the one the natural {@code @Transactional}-on-the-handler idiom
   * gets wrong.
   */
  <T> T execute(UUID roomId, RoomMutation<T> mutation) {
    ReentrantLock lock = acquireLock(roomId);
    try {
      long epoch =
          ownership
              .epochOf(roomId)
              .orElseThrow(
                  () ->
                      new RoomUnavailableRetryException(
                          "This instance does not own room " + roomId + ".", RETRY_AFTER_MS));
      Committed<T> committed;
      try {
        committed =
            transactionTemplate.execute(
                status -> {
                  MutationOutcome<T> outcome = mutation.apply();
                  if (outcome.event() == null) {
                    return new Committed<>(outcome.value(), null, 0);
                  }
                  long version = ownership.nextVersion(roomId);
                  int updated =
                      jdbcClient
                          .sql(
                              """
                              update room set version = :version, last_activity_at = now()
                               where room_id = :roomId and version = :expected
                                 and owner_epoch = :epoch
                              """)
                          .param("version", version)
                          .param("roomId", roomId)
                          .param("expected", version - 1)
                          .param("epoch", epoch)
                          .update();
                  if (updated == 0) {
                    status.setRollbackOnly();
                    throw new FencedCommitFailed(roomId);
                  }
                  return new Committed<>(outcome.value(), outcome.event(), version);
                });
      } catch (FencedCommitFailed e) {
        // room-lifecycle.md §2.1: a failed CAS leaves the in-memory room state dirty — this
        // instance's own view of the room, including its version counter, can no longer be
        // trusted. Evict rather than repair; the next claim re-initializes from the database.
        ownership.evictAfterFailedCas(roomId);
        throw new RoomUnavailableRetryException(
            "Lost ownership of room " + roomId + " mid-mutation.", RETRY_AFTER_MS);
      }
      Committed<T> result = requireCommitted(committed);
      if (result.event() != null) {
        publish(roomId, result.event(), result.version());
      }
      return result.value();
    } finally {
      lock.unlock();
    }
  }

  /**
   * Lock + fresh ownership check only — no transaction, no version bump, no publish. For
   * lock-serialized reads that must still fail the same way a stale-owner pod's mutation would,
   * matching every other STOMP command handler's authoritative re-check ({@code
   * room-chat-websocket.md}: sender team resolution and monotonic {@code serverTimestamp}
   * assignment — {@code chat} publishes to its own topic itself, since {@link #execute} only ever
   * publishes to the bare room topic).
   */
  <T> T withLock(UUID roomId, Supplier<T> op) {
    ReentrantLock lock = acquireLock(roomId);
    try {
      Long unused =
          ownership
              .epochOf(roomId)
              .orElseThrow(
                  () ->
                      new RoomUnavailableRetryException(
                          "This instance does not own room " + roomId + ".", RETRY_AFTER_MS));
      return op.get();
    } finally {
      lock.unlock();
    }
  }

  private ReentrantLock acquireLock(UUID roomId) {
    ReentrantLock lock = locks.computeIfAbsent(roomId, id -> new ReentrantLock());
    boolean acquired;
    try {
      acquired = lock.tryLock(LOCK_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RoomUnavailableRetryException(
          "Interrupted while waiting for the room lock.", RETRY_AFTER_MS);
    }
    if (!acquired) {
      // A lock-acquisition timeout is not proof of lost ownership (room-lifecycle.md §2.1) — fail
      // the command, publish nothing, and do not evict.
      throw new RoomUnavailableRetryException(
          "Timed out waiting for room " + roomId + " to become available.", RETRY_AFTER_MS);
    }
    return lock;
  }

  private void publish(UUID roomId, RoomEvent event, long version) {
    logLifecycle(roomId, event, version);
    messagingTemplate.convertAndSend(
        "/topic/room." + roomId,
        (Object) Map.of("type", event.type(), "version", version, "payload", event.payload()));
  }

  /**
   * MR-06: every committed room event is one INFO line carrying {@code roomId}, the affected {@code
   * userId} where the payload names one, and the close/loss reason. Join, leave, disconnect, grace
   * expiry ({@code PLAYER_LOST}), reconnect and close all pass through here, so none can be
   * published unlogged. Never the payload itself: it can carry usernames and chat-adjacent text.
   */
  private static void logLifecycle(UUID roomId, RoomEvent event, long version) {
    UUID userId = null;
    String reason = null;
    Object sessionId = null;
    switch (event.payload()) {
      case RoomRepository.PlayerJoinedPayload p -> userId = p.playerId();
      case RoomRepository.PlayerLeftPayload p -> userId = p.playerId();
      case RoomRepository.PlayerDisconnectedPayload p -> userId = p.playerId();
      case RoomRepository.PlayerReconnectedPayload p -> userId = p.playerId();
      case RoomRepository.PlayerKickedPayload p -> userId = p.playerId();
      case RoomRepository.PlayerLostPayload p -> {
        userId = p.playerId();
        reason = p.reason();
      }
      case RoomRepository.HostTransferredPayload p -> {
        userId = p.newHostId();
        reason = p.reason();
      }
      case RoomRepository.RoomClosedPayload p -> reason = p.reason();
      case Map<?, ?> m -> sessionId = m.get("sessionId");
      default -> {}
    }
    LifecycleLog.info(
        log,
        "Room event",
        "event",
        event.type(),
        "roomId",
        roomId,
        "userId",
        userId,
        "reason",
        reason,
        "sessionId",
        sessionId,
        "roomVersion",
        version);
  }

  private static <T> Committed<T> requireCommitted(@Nullable Committed<T> committed) {
    if (committed == null) {
      throw new IllegalStateException("transactionTemplate.execute returned null without a status");
    }
    return committed;
  }

  /** The caller's unit of work: run inside the lock and the transaction, before the CAS. */
  interface RoomMutation<T> {
    MutationOutcome<T> apply();
  }

  /**
   * {@code event} is {@code null} for a mutation that changed nothing (an already-current
   * participant re-joining, a departure that was already recorded) — the executor skips the version
   * bump and the publish entirely rather than broadcasting a no-op.
   */
  record MutationOutcome<T>(T value, @Nullable RoomEvent event) {}

  /** {@code type}/{@code payload} of a {@code room-websocket.md} Server→Client event. */
  record RoomEvent(String type, Object payload) {}

  private record Committed<T>(T value, @Nullable RoomEvent event, long version) {}

  /** Internal signal only — never escapes {@link #execute}. */
  private static final class FencedCommitFailed extends RuntimeException {
    FencedCommitFailed(UUID roomId) {
      super("fenced version commit affected no rows for room " + roomId, null, false, false);
    }
  }
}
