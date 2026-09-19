package io.turnbase.platform.upload;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * {@code bundle-format.md} §7.2's four sweep duties — no requirement defines this job (§9 D3), so
 * its duties are exactly those named there. Duty 2 is the fix for the Done-when clause that a
 * died-mid-validation version number is not permanently burned: a {@code PROCESSING} row whose
 * lease ({@code claimed_until}) has lapsed moves to {@code FAILED}, which {@code §7.4} then lets a
 * fresh slot request reclaim. Mirrors {@code room.RoomPurgeJob} + {@code
 * RoomPurgeSchedulingConfig}'s shape.
 */
@Component
class UploadSlotCleanupJob {

  private static final Logger log = LoggerFactory.getLogger(UploadSlotCleanupJob.class);

  private final GameVersionRepository repository;
  private final ArtifactStore artifactStore;

  UploadSlotCleanupJob(GameVersionRepository repository, ArtifactStore artifactStore) {
    this.repository = repository;
    this.artifactStore = artifactStore;
  }

  @SchedulerLock(name = "uploadSlotCleanup")
  void run() {
    int expired = repository.expirePendingSlots().size();
    int abandoned = repository.failAbandonedProcessing().size();
    if (expired > 0 || abandoned > 0) {
      log.info(
          "upload slot cleanup: expired {} pending slot(s), failed {} abandoned lease(s)",
          expired,
          abandoned);
    }

    for (GameVersionRepository.PurgeCandidate candidate : repository.findUnpurgedTerminal()) {
      purgeBundle(candidate);
    }

    for (GameVersionRepository.PurgeCandidate candidate : repository.findOrphanCandidates()) {
      purgeOrphanedTrees(candidate);
    }
  }

  /**
   * §7.3: a {@code FAILED}/{@code EXPIRED} row's bundle object is purged, best-effort — a failed
   * delete is retried by the next sweep tick rather than being forgotten.
   */
  private void purgeBundle(GameVersionRepository.PurgeCandidate candidate) {
    String key = candidate.bundleObjectKey();
    if (key == null) {
      repository.markBundlePurged(candidate.versionId());
      return;
    }
    try {
      artifactStore.delete(key);
      repository.markBundlePurged(candidate.versionId());
    } catch (RuntimeException e) {
      log.warn(
          "upload slot cleanup: failed to purge bundle object {} for version {}",
          key,
          candidate.versionId(),
          e);
    }
  }

  /**
   * Duty 4: the collector for a crash during §5.3 steps 1-3 — best-effort, never blocks duties 1-3.
   */
  private void purgeOrphanedTrees(GameVersionRepository.PurgeCandidate candidate) {
    try {
      artifactStore.deletePrefix(
          ObjectKeys.unpackedPrefix(candidate.gameId(), candidate.version()));
      artifactStore.deletePrefix(
          ObjectKeys.artifactsPrefix(candidate.gameId(), candidate.version()));
    } catch (RuntimeException e) {
      log.warn(
          "upload slot cleanup: failed to purge orphaned trees for {}/{}",
          candidate.gameId(),
          candidate.version(),
          e);
    }
  }
}
