package io.turnbase.platform.room;

import io.turnbase.platform.common.JobsProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * {@code room-lifecycle.md} §5.2 (FR-JOB-007) — the 60s, single-instance reconciliation sweep,
 * scheduled by {@link ReconciliationSchedulingConfig}. Five duties, run in this order every tick;
 * an exception from any datastore call aborts the whole remaining run (§5.4 guard 1 — "an error is
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
   * {@code game-host-internals.md} §13 — the six key families Orchestration/the Game Host write per
   * session, all sharing one lifecycle from duty (b)'s point of view: present or absent together,
   * deleted together.
   */
  private static final List<String> SESSION_KEY_PREFIXES =
      List.of(
          "session:",
          "routing:",
          "gamekey:",
          "queue:",
          "seq:",
          "delivered:",
          "failcount:",
          "gamelog:");

  private static final int TEARDOWN_BATCH = 100;

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

  private void reconcileOrphanedSessionArtifacts(boolean massLoss) {
    Set<String> sessionIds = new LinkedHashSet<>();
    for (String prefix : SESSION_KEY_PREFIXES) {
      Set<String> keys = metrics.recordRedis(() -> redis.keys(prefix + "*"));
      if (keys == null) {
        continue;
      }
      for (String key : keys) {
        sessionIds.add(key.substring(prefix.length()));
      }
    }

    Duration orphanAge = jobsProperties.reconcile().orphanAge();
    long candidateCount = 0;
    long repaired = 0;
    for (String raw : sessionIds) {
      UUID sessionId;
      try {
        sessionId = UUID.fromString(raw);
      } catch (IllegalArgumentException e) {
        continue; // not one of ours
      }
      if (repository.sessionBelongsToOpenRoom(sessionId)) {
        repository.clearOrphanedSessionFlag(sessionId);
        continue;
      }
      candidateCount++;
      Optional<Instant> firstObserved = repository.orphanedSessionFirstObservedAt(sessionId);
      if (firstObserved.isEmpty()) {
        repository.flagOrphanedSessionIfAbsent(sessionId);
        metrics.recordRepair("b", "detected");
        continue;
      }
      if (Duration.between(firstObserved.get(), Instant.now()).compareTo(orphanAge) < 0) {
        metrics.recordRepair("b", "detected");
        continue;
      }
      deleteSessionKeyFamily(sessionId);
      repository.clearOrphanedSessionFlag(sessionId);
      repaired++;
      metrics.recordRepair("b", "repaired");
      if (!massLoss) {
        log.warn("reconciliation duty (b): deleted orphaned session key family {}", sessionId);
      }
    }
    metrics.setCandidates("b", candidateCount);
    if (massLoss && repaired > 0) {
      log.error(
          "reconciliation duty (b): {} orphaned session key families repaired as one mass-loss"
              + " event",
          repaired);
    }
  }

  private void deleteSessionKeyFamily(UUID sessionId) {
    for (String prefix : SESSION_KEY_PREFIXES) {
      String key = prefix + sessionId;
      metrics.recordRedis(() -> redis.delete(key));
    }
  }

  // ---------------------------------------------------------------------
  // Duty (c) — In Progress, no session record. Two-strike via room.reconcile_flagged_at.
  // ---------------------------------------------------------------------

  private void reconcileInProgressWithoutSession(boolean suppressed, boolean massLoss) {
    List<RoomRepository.RoomSessionPair> candidates = new ArrayList<>();
    for (RoomRepository.RoomSessionPair pair : repository.inProgressRoomsWithSession()) {
      // room-lifecycle.md §5.2: "No Game Host probe. The session record is the source of truth."
      boolean sessionRecordPresent =
          Boolean.TRUE.equals(
              metrics.recordRedis(() -> redis.hasKey("session:" + pair.sessionId())));
      if (sessionRecordPresent) {
        repository.clearRoomReconcileFlag(pair.roomId());
      } else {
        candidates.add(pair);
      }
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
}
