package io.turnbase.platform.room;

import static java.nio.charset.StandardCharsets.UTF_8;

import io.turnbase.platform.common.JobsProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * {@code room-lifecycle.md} §5.2 (FR-JOB-007) — the 60s, single-instance reconciliation sweep,
 * scheduled by {@link ReconciliationSchedulingConfig}. Six duties, run in this order every tick; an
 * exception from any datastore call aborts the whole remaining run (§5.4 guard 1 — "an error is
 * never read as an absence").
 *
 * <p>{@code @SchedulerLock} gives FR-JOB-005/FR-JOB-007's "exactly one instance per interval," the
 * same {@code account.GuestPurgeJob} precedent — unlike {@link GraceDeadlineSweepJob}, this sweep
 * is not ownership-partitioned; every duty here either reads state no single owner is authoritative
 * for (orphaned artifacts, idle rooms) or is itself about *finding* a missing owner (adoption).
 */
@Component
class ReconciliationJob {

  private static final Logger log = LoggerFactory.getLogger(ReconciliationJob.class);

  /**
   * {@code game-host-internals.md} §13 — the seven key families Orchestration/the Game Host write
   * per session, all sharing one lifecycle from duty (b)'s point of view: present or absent
   * together, deleted together.
   */
  private static final List<String> SESSION_KEY_PREFIXES =
      List.of(
          "session:",
          "routing:",
          "gamekey:",
          "roster:",
          "queue:",
          "seq:",
          "delivered:",
          "failcount:",
          "gamelog:");

  private static final int TEARDOWN_BATCH = 100;

  /** Keys per {@code SCAN} page in duty (b)'s keyspace pass. */
  private static final int SCAN_COUNT = 500;

  /** Sessions per Postgres query batch in duty (b), and rooms per batch in duty (c). */
  private static final int SESSION_BATCH = 500;

  /** Keys per {@code SCAN} page and rooms per Postgres existence query in the room-key sweep. */
  private static final int ROOM_KEY_BATCH = 500;

  private static final int ROOM_KEY_PREFIX_LENGTH = "room:".length();

  private final RoomRepository repository;
  private final RoomOwnershipService ownership;
  private final RoomSessionDetacher detacher;
  private final SessionTerminator terminator;
  private final StringRedisTemplate redis;
  private final PodIdentity pod;
  private final JobsProperties jobsProperties;
  private final RoomProperties roomProperties;
  private final ReconciliationMetrics metrics;

  ReconciliationJob(
      RoomRepository repository,
      RoomOwnershipService ownership,
      RoomSessionDetacher detacher,
      SessionTerminator terminator,
      StringRedisTemplate redis,
      PodIdentity pod,
      JobsProperties jobsProperties,
      RoomProperties roomProperties,
      ReconciliationMetrics metrics) {
    this.repository = repository;
    this.ownership = ownership;
    this.detacher = detacher;
    this.terminator = terminator;
    this.redis = redis;
    this.pod = pod;
    this.jobsProperties = jobsProperties;
    this.roomProperties = roomProperties;
    this.metrics = metrics;
  }

  @SchedulerLock(name = "roomReconciliation")
  void run() {
    try {
      // room-lifecycle.md §5.4, additionally: platform:sentinel absent while Redis answers means
      // the datastore was reset. Repairs still proceed this tick, but as one mass-loss event
      // rather than one WARN per room — the sentinel is re-set only once that reporting is done,
      // so a genuinely still-absent Redis is re-detected on every subsequent tick.
      boolean massLoss = !ownership.sentinelPresent();
      if (massLoss) {
        log.error(
            "reconciliation: platform:sentinel is absent while Redis is reachable — the datastore"
                + " was reset; repairs this run are reported as a single mass-loss event");
      }
      boolean suppressed = withinStartupSuppression();

      driveOwedTeardowns();
      reconcileOrphanedSessionArtifacts(massLoss);
      reconcileInProgressWithoutSession(suppressed, massLoss);
      reconcileIdleLobby(suppressed, massLoss);
      adoptUnownedRooms();
      sweepClosedRoomKeys();

      if (massLoss) {
        ownership.ensureSentinel();
      }
    } catch (RuntimeException e) {
      // §5.4 guard 1: never read an error as an absence. Nothing repaired this tick beyond
      // whatever already committed before the failing call.
      String reason = abortReason(e);
      metrics.recordAborted("reconciliation", reason);
      log.warn("reconciliation run aborted ({}): {}", reason, e.toString());
    }
  }

  private boolean withinStartupSuppression() {
    return Instant.now()
        .isBefore(pod.bootAt().plus(jobsProperties.reconcile().startupSuppression()));
  }

  private static String abortReason(RuntimeException e) {
    String className = e.getClass().getName().toLowerCase(Locale.ROOT);
    return className.contains("redis") ? "redis_unavailable" : "database_unavailable";
  }

  // ---------------------------------------------------------------------
  // Duty (a) — owed teardown re-drive. No FR-JOB-008 guard: session_teardown's own next_attempt_at
  // backoff is the only pacing this duty needs (STATUS.md session 48 — a confirmed durable debt is
  // not an inferred divergence).
  // ---------------------------------------------------------------------

  private void driveOwedTeardowns() {
    List<RoomRepository.DueTeardown> due = repository.dueSessionTeardowns(TEARDOWN_BATCH);
    metrics.setCandidates("a", due.size());
    for (RoomRepository.DueTeardown teardown : due) {
      terminator.terminateAndCleanup(teardown.sessionId(), teardown.reason());
      metrics.recordRepair("a", "retried");
    }
    metrics.setTeardownOwed(
        repository.countSessionTeardownOwed(), repository.oldestSessionTeardownAgeSeconds());
  }

  // ---------------------------------------------------------------------
  // Duty (b) — orphaned Redis session-key families. Two-strike via orphaned_session_flag
  // (IMP-GATE-56), the age threshold doubling as the two-strike corroboration since orphan-age
  // (10m default) vastly exceeds the 60s tick interval.
  // ---------------------------------------------------------------------

  /**
   * One {@code SCAN} pass over the whole keyspace (never {@code KEYS}, never one pass per family),
   * each key classified by its prefix here, and one batch of Postgres queries per {@link
   * #SESSION_BATCH} sessions rather than one per session (IMP-M16-09, load-testing finding 15).
   */
  void reconcileOrphanedSessionArtifacts(boolean massLoss) {
    Duration orphanAge = jobsProperties.reconcile().orphanAge();
    Set<UUID> seen = new HashSet<>();
    Set<UUID> page = new LinkedHashSet<>();
    long[] counts = new long[2]; // {candidates, repaired}
    ScanOptions options = ScanOptions.scanOptions().count(SCAN_COUNT).build();
    try (Cursor<String> cursor = metrics.recordRedis(() -> redis.scan(options))) {
      while (cursor.hasNext()) {
        UUID sessionId = sessionIdOf(cursor.next());
        if (sessionId == null || !seen.add(sessionId)) {
          continue;
        }
        page.add(sessionId);
        if (page.size() >= SESSION_BATCH) {
          reconcileSessionPage(page, orphanAge, massLoss, counts);
          page.clear();
        }
      }
    }
    reconcileSessionPage(page, orphanAge, massLoss, counts);
    metrics.setCandidates("b", counts[0]);
    if (massLoss && counts[1] > 0) {
      log.error(
          "reconciliation duty (b): {} orphaned session key families repaired as one mass-loss"
              + " event",
          counts[1]);
    }
  }

  /** The session id of a {@code <family>:<uuid>} key of {@link #SESSION_KEY_PREFIXES}, or null. */
  private static @Nullable UUID sessionIdOf(String key) {
    int colon = key.indexOf(':');
    if (colon < 0 || !SESSION_KEY_PREFIXES.contains(key.substring(0, colon + 1))) {
      return null;
    }
    try {
      return UUID.fromString(key.substring(colon + 1));
    } catch (IllegalArgumentException e) {
      return null; // not one of ours
    }
  }

  private void reconcileSessionPage(
      Set<UUID> page, Duration orphanAge, boolean massLoss, long[] counts) {
    if (page.isEmpty()) {
      return;
    }
    Set<UUID> live = repository.sessionsOfOpenRooms(page);
    Map<UUID, Instant> flagged = repository.orphanedSessionFirstObservedAt(page);
    List<UUID> toFlag = new ArrayList<>();
    List<UUID> toDelete = new ArrayList<>();
    List<UUID> toClear = new ArrayList<>();
    for (UUID sessionId : page) {
      Instant firstObserved = flagged.get(sessionId);
      if (live.contains(sessionId)) {
        if (firstObserved != null) {
          toClear.add(sessionId);
        }
        continue;
      }
      counts[0]++;
      if (firstObserved == null) {
        toFlag.add(sessionId);
        metrics.recordRepair("b", "detected");
      } else if (Duration.between(firstObserved, Instant.now()).compareTo(orphanAge) < 0) {
        metrics.recordRepair("b", "detected");
      } else {
        toDelete.add(sessionId);
      }
    }
    repository.flagOrphanedSessionsIfAbsent(toFlag);
    if (!toDelete.isEmpty()) {
      deleteSessionKeyFamilies(toDelete);
      toClear.addAll(toDelete);
      for (UUID sessionId : toDelete) {
        counts[1]++;
        metrics.recordRepair("b", "repaired");
        if (!massLoss) {
          log.warn("reconciliation duty (b): deleted orphaned session key family {}", sessionId);
        }
      }
    }
    repository.clearOrphanedSessionFlags(toClear);
  }

  private void deleteSessionKeyFamilies(List<UUID> sessionIds) {
    List<String> keys = new ArrayList<>(sessionIds.size() * SESSION_KEY_PREFIXES.size());
    for (UUID sessionId : sessionIds) {
      for (String prefix : SESSION_KEY_PREFIXES) {
        keys.add(prefix + sessionId);
      }
    }
    metrics.recordRedis(() -> redis.delete(keys));
  }

  // ---------------------------------------------------------------------
  // Duty (c) — In Progress, no session record. Two-strike via room.reconcile_flagged_at.
  // ---------------------------------------------------------------------

  private void reconcileInProgressWithoutSession(boolean suppressed, boolean massLoss) {
    List<RoomRepository.RoomSessionPair> candidates = new ArrayList<>();
    List<RoomRepository.RoomSessionPair> inProgress = repository.inProgressRoomsWithSession();
    for (int from = 0; from < inProgress.size(); from += SESSION_BATCH) {
      List<RoomRepository.RoomSessionPair> page =
          inProgress.subList(from, Math.min(from + SESSION_BATCH, inProgress.size()));
      // room-lifecycle.md §5.2: "No Game Host probe. The session record is the source of truth."
      List<Boolean> present = sessionRecordsPresent(page);
      List<UUID> healthy = new ArrayList<>();
      for (int i = 0; i < page.size(); i++) {
        if (present.get(i)) {
          healthy.add(page.get(i).roomId());
        } else {
          candidates.add(page.get(i));
        }
      }
      repository.clearRoomReconcileFlags(healthy);
    }
    metrics.setCandidates("c", candidates.size());
    metrics.setInProgressWithoutSession(candidates.size());
    if (suppressed) {
      return;
    }
    for (RoomRepository.RoomSessionPair pair : candidates) {
      if (repository.roomReconcileFlaggedAt(pair.roomId()).isEmpty()) {
        repository.flagRoomForReconcileIfAbsent(pair.roomId());
        metrics.recordRepair("c", "detected");
        continue;
      }
      detacher.detach(pair.roomId(), pair.sessionId(), "SESSION_STATE_LOST", "LOBBY");
      repository.clearRoomReconcileFlag(pair.roomId());
      metrics.recordRepair("c", "repaired");
      if (!massLoss) {
        log.warn(
            "reconciliation duty (c): room {} failed back to Lobby, session {} state was gone",
            pair.roomId(),
            pair.sessionId());
      }
    }
  }

  /** One pipelined {@code EXISTS} round trip for a page of {@code session:<id>} records. */
  @SuppressWarnings("NullAway") // executePipelined requires the callback to return null
  private List<Boolean> sessionRecordsPresent(List<RoomRepository.RoomSessionPair> page) {
    List<Object> replies =
        metrics.recordRedis(
            () ->
                redis.executePipelined(
                    (RedisCallback<Object>)
                        connection -> {
                          for (RoomRepository.RoomSessionPair pair : page) {
                            connection
                                .keyCommands()
                                .exists(("session:" + pair.sessionId()).getBytes(UTF_8));
                          }
                          return null;
                        }));
    List<Boolean> present = new ArrayList<>(page.size());
    for (Object reply : replies) {
      present.add(Boolean.TRUE.equals(reply) || (reply instanceof Number n && n.longValue() > 0));
    }
    return present;
  }

  // ---------------------------------------------------------------------
  // Duty (d) — idle Lobby. Two-strike via room.reconcile_flagged_at, shared with duty (c): a room
  // cannot be both IN_PROGRESS and LOBBY, so the column is never ambiguous between the two.
  // ---------------------------------------------------------------------

  private void reconcileIdleLobby(boolean suppressed, boolean massLoss) {
    List<UUID> candidates = repository.idleLobbyRoomIds(roomProperties.idleWindow());
    metrics.setCandidates("d", candidates.size());
    if (suppressed) {
      return;
    }
    for (UUID roomId : candidates) {
      if (repository.roomReconcileFlaggedAt(roomId).isEmpty()) {
        repository.flagRoomForReconcileIfAbsent(roomId);
        metrics.recordRepair("d", "detected");
        continue;
      }
      detacher.detach(roomId, repository.activeSessionId(roomId), "IDLE_TIMEOUT", "CLOSED");
      repository.clearRoomReconcileFlag(roomId);
      metrics.recordRepair("d", "repaired");
      if (!massLoss) {
        log.warn("reconciliation duty (d): idle Lobby room {} closed (IDLE_TIMEOUT)", roomId);
      }
    }
  }

  // ---------------------------------------------------------------------
  // Duty (e) — unowned room adoption. Exempt from both FR-JOB-008 guards (flow 05 §D): an absent
  // liveness lease is the claim script's own precondition, not a corroborated inference.
  // ---------------------------------------------------------------------

  private void adoptUnownedRooms() {
    List<String> deadOwners = new ArrayList<>();
    for (String instanceId : repository.distinctLiveOwnerInstanceIds()) {
      if (!ownership.isInstanceAlive(instanceId)) {
        deadOwners.add(instanceId);
      }
    }
    List<UUID> candidates = repository.unownedRoomIds(deadOwners);
    metrics.setCandidates("e", candidates.size());
    for (UUID roomId : candidates) {
      RoomOwnershipService.ClaimOutcome outcome = ownership.claim(roomId);
      if (outcome instanceof RoomOwnershipService.ClaimOutcome.Adopted) {
        metrics.recordRepair("e", "adopted");
        log.warn("reconciliation duty (e): adopted unowned room {}", roomId);
      }
    }
  }

  // ---------------------------------------------------------------------
  // Duty (f) — ownership keys of closed rooms (IMP-M16-08). The close hook deletes them in the
  // normal case; this collects what a crash, a Redis outage at close or a claim of an already
  // closed room left behind. CLOSED is terminal, so no two-strike corroboration is needed: the
  // database says so, and a room row is committed before anything can claim its id.
  // ---------------------------------------------------------------------

  /**
   * {@code SCAN}s {@code room:*:pod} and {@code room:*:epoch} (never {@code KEYS}) and deletes the
   * keys of rooms that are {@code CLOSED} or no longer exist, one existence query per batch.
   */
  void sweepClosedRoomKeys() {
    long candidates = 0;
    long deleted = 0;
    for (String suffix : List.of(":pod", ":epoch")) {
      ScanOptions options =
          ScanOptions.scanOptions().match("room:*" + suffix).count(ROOM_KEY_BATCH).build();
      Map<UUID, String> batch = new LinkedHashMap<>();
      try (Cursor<String> cursor = metrics.recordRedis(() -> redis.scan(options))) {
        while (cursor.hasNext()) {
          String key = cursor.next();
          UUID roomId = roomIdOf(key, suffix);
          if (roomId != null) {
            batch.put(roomId, key);
          }
          if (batch.size() >= ROOM_KEY_BATCH) {
            long[] counts = deleteKeysOfClosedRooms(batch);
            candidates += counts[0];
            deleted += counts[1];
            batch.clear();
          }
        }
      }
      long[] counts = deleteKeysOfClosedRooms(batch);
      candidates += counts[0];
      deleted += counts[1];
    }
    metrics.setCandidates("f", candidates);
    if (deleted > 0) {
      metrics.recordRepair("f", "repaired", deleted);
      log.info("reconciliation duty (f): deleted {} ownership keys of closed rooms", deleted);
    }
  }

  /** {@code [orphans found, keys deleted]} for one batch of {@code room id -> key}. */
  private long[] deleteKeysOfClosedRooms(Map<UUID, String> batch) {
    if (batch.isEmpty()) {
      return new long[] {0, 0};
    }
    Set<UUID> open = repository.openRoomIds(batch.keySet());
    List<String> orphaned = new ArrayList<>();
    batch.forEach(
        (roomId, key) -> {
          if (!open.contains(roomId)) {
            orphaned.add(key);
          }
        });
    if (orphaned.isEmpty()) {
      return new long[] {0, 0};
    }
    Long removed = metrics.recordRedis(() -> redis.delete(orphaned));
    return new long[] {orphaned.size(), removed == null ? 0 : removed};
  }

  private static @Nullable UUID roomIdOf(String key, String suffix) {
    try {
      return UUID.fromString(key.substring(ROOM_KEY_PREFIX_LENGTH, key.length() - suffix.length()));
    } catch (IllegalArgumentException | StringIndexOutOfBoundsException e) {
      return null; // not one of ours
    }
  }
}
