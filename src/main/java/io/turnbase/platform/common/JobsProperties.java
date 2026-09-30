package io.turnbase.platform.common;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The scheduled-job intervals of local-environment.md §7.2. Kept as one cross-cutting properties
 * class rather than split across the features each job eventually belongs to (room, session,
 * notification, upload) — §7.2 is itself one table, and none of the owning jobs exist yet to bind
 * against.
 */
@ConfigurationProperties(prefix = "turnbase.jobs")
public record JobsProperties(
    Interval deadlineSweep,
    Reconcile reconcile,
    Interval guestCleanup,
    Interval notificationPurge,
    Interval expiryPurge,
    Interval roomPurge,
    Interval uploadSlotCleanup) {

  /**
   * FR-JOB-008(a): a purge job's first run waits out the startup window ({@link
   * Reconcile#startupSuppression()}) — or a full interval, when that is longer.
   */
  public Duration initialDelay(Interval job) {
    Duration suppression = reconcile.startupSuppression();
    return job.interval().compareTo(suppression) >= 0 ? job.interval() : suppression;
  }

  public record Interval(Duration interval) {}

  public record Reconcile(Duration interval, Duration startupSuppression, Duration orphanAge) {}
}
