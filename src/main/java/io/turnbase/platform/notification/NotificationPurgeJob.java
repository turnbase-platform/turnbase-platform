package io.turnbase.platform.notification;

import io.turnbase.platform.common.RetentionProperties;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * FR-JOB-002 / FR-RET-001: deletes notifications past {@code turnbase.retention.notification},
 * scheduled by {@link NotificationPurgeSchedulingConfig} on {@code
 * turnbase.jobs.notification-purge.interval}. Actionable notifications are excluded by the
 * partial-index predicate and are removed only by {@link ExpiredSubjectPurgeJob}.
 */
@Component
class NotificationPurgeJob {

  private static final Logger log = LoggerFactory.getLogger(NotificationPurgeJob.class);

  private final NotificationPurgeRepository repository;
  private final RetentionProperties retentionProperties;

  NotificationPurgeJob(
      NotificationPurgeRepository repository, RetentionProperties retentionProperties) {
    this.repository = repository;
    this.retentionProperties = retentionProperties;
  }

  @SchedulerLock(name = "notificationPurge")
  void run() {
    int purged = repository.purgeSubjectlessOlderThan(retentionProperties.notification());
    if (purged > 0) {
      log.info("notification purge: deleted {} notification(s) past retention", purged);
    }
  }
}
