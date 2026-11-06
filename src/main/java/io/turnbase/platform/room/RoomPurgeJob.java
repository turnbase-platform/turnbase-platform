package io.turnbase.platform.room;

import io.turnbase.platform.common.RetentionProperties;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * FR-JOB-009 / {@code room-lifecycle.md} §5.3: permanently deletes {@code CLOSED} rooms past {@code
 * turnbase.retention.closed-room}, scheduled by {@link RoomPurgeSchedulingConfig} on {@code
 * turnbase.jobs.room-purge.interval}. Participant, team and invite rows cascade ({@code
 * data-model.md} §1's FK definitions); game history carries no room foreign key and is untouched
 * (FR-RET-006). {@link SchedulerLock} matches {@code account.GuestPurgeJob}'s precedent for a job
 * with no ownership partition to exploit.
 */
@Component
class RoomPurgeJob {

  private static final Logger log = LoggerFactory.getLogger(RoomPurgeJob.class);

  private final RoomOutboxRepository repository;
  private final RetentionProperties retentionProperties;

  RoomPurgeJob(RoomOutboxRepository repository, RetentionProperties retentionProperties) {
    this.repository = repository;
    this.retentionProperties = retentionProperties;
  }

  @SchedulerLock(name = "roomPurge")
  void run() {
    int purged = repository.purgeClosedRooms(retentionProperties.closedRoom());
    if (purged > 0) {
      log.info("room purge: deleted {} closed room(s) past retention", purged);
    }
  }
}
