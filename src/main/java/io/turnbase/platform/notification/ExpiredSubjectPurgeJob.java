package io.turnbase.platform.notification;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * FR-JOB-013: permanently deletes {@code friend_request} and {@code room_invitation} rows whose
 * {@code expires_at} has passed. Deleting the subject cascades its actionable notification away,
 * which makes this the only mechanism that ever removes one (IR-20): {@code notification_purge_idx}
 * excludes them from {@link NotificationPurgeJob}. Scheduled by {@link
 * NotificationPurgeSchedulingConfig} on {@code turnbase.jobs.expiry-purge.interval}.
 */
@Component
class ExpiredSubjectPurgeJob {

  private static final Logger log = LoggerFactory.getLogger(ExpiredSubjectPurgeJob.class);

  private final NotificationPurgeRepository repository;

  ExpiredSubjectPurgeJob(NotificationPurgeRepository repository) {
    this.repository = repository;
  }

  @SchedulerLock(name = "expiredSubjectPurge")
  void run() {
    int requests = repository.purgeExpiredFriendRequests();
    int invitations = repository.purgeExpiredRoomInvitations();
    if (requests > 0 || invitations > 0) {
      log.info(
          "expiry purge: deleted {} friend request(s) and {} room invitation(s)",
          requests,
          invitations);
    }
  }
}
