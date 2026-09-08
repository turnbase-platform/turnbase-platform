package io.turnbase.platform.account;

import io.turnbase.platform.common.RetentionProperties;
import io.turnbase.platform.session.SessionService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * FR-GUEST-005 / {@code platform-operations.md} FR-RET-002: the sweep behind {@code
 * data-model-accounts.md} §1's two-phase guest purge, scheduled by {@link
 * GuestPurgeSchedulingConfig} on {@code turnbase.jobs.guest-cleanup.interval}. Both phases run on
 * every invocation — the purge phase is cheap to attempt and idempotent when nothing is eligible,
 * so there is no reason to stagger them onto separate intervals the registry never asked for.
 *
 * <p>{@link SchedulerLock} gives FR-JOB-005's "exactly one instance per interval" over the {@code
 * shedlock} table the baseline migration already carries for this.
 */
@Component
class GuestPurgeJob {

  private static final Logger log = LoggerFactory.getLogger(GuestPurgeJob.class);

  private final GuestPurgeRepository repository;
  private final SessionService sessionService;
  private final RetentionProperties retentionProperties;

  GuestPurgeJob(
      GuestPurgeRepository repository,
      SessionService sessionService,
      RetentionProperties retentionProperties) {
    this.repository = repository;
    this.sessionService = sessionService;
    this.retentionProperties = retentionProperties;
  }

  @SchedulerLock(name = "guestPurge")
  void run() {
    Instant now = Instant.now();
    Instant cutoff = now.minus(retentionProperties.guestAccount());

    List<UUID> expired = repository.findExpiredGuestUserIds(cutoff);
    for (UUID userId : expired) {
      // Sessions first: SessionAuthenticationFilter never consults app_user, so revoking is the
      // actual inaccessibility mechanism (FR-GUEST-005). Marking app_user first and crashing
      // before this step would leave a valid session for a "deleted" guest indefinitely, since
      // the next run's WHERE deleted_at is null would then skip the row.
      sessionService.revokeAllForUser(userId, SessionService.GUEST_EXPIRED_REASON);
      repository.markExpired(userId, now);
    }
    if (!expired.isEmpty()) {
      log.info("guest purge: revoked {} expired guest account(s)", expired.size());
    }

    List<UUID> purged = repository.purgeUnreferencedGuests();
    if (!purged.isEmpty()) {
      log.info("guest purge: hard-deleted {} guest account(s)", purged.size());
    }
  }
}
