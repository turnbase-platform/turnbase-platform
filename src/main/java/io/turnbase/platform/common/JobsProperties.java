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

  public record Interval(Duration interval) {}

  public record Reconcile(Duration interval, Duration startupSuppression, Duration orphanAge) {}
}
