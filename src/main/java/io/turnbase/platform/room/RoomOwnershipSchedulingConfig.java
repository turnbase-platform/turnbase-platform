package io.turnbase.platform.room;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.PeriodicTrigger;

/**
 * Writes this instance's liveness key once at startup (before the {@code SET ... XX} renewal loop
 * can run — {@code XX} would never succeed against a key that has never existed) and then schedules
 * the two ownership tasks every {@code turnbase.room.lease.renew-interval}.
 *
 * <p>Two single-thread schedulers, neither of them Spring's shared one: {@code lease-renewer} runs
 * the one-command lease renewal, and {@code ownership-recheck} the per-room {@code MGET} walk. A
 * re-check that is slow, blocked, or stuck behind a large {@code owned} set therefore cannot delay
 * the renewal past the lease TTL, and neither can any other {@code @Scheduled} job on the shared
 * pool. The schedulers are fields rather than beans: a {@code TaskScheduler} bean would switch off
 * Boot's own scheduler and its {@code spring.task.scheduling.pool.size}.
 *
 * <p>No {@code @SchedulerLock}: this must run on **every** instance, not be claimed by one — it is
 * each pod renewing its own liveness key, not a cluster-wide job.
 */
@Configuration
class RoomOwnershipSchedulingConfig {

  private final RoomOwnershipService ownership;
  private final RoomProperties properties;
  private final ThreadPoolTaskScheduler leaseRenewer = scheduler("lease-renewer-");
  private final ThreadPoolTaskScheduler ownershipRecheck = scheduler("ownership-recheck-");

  RoomOwnershipSchedulingConfig(RoomOwnershipService ownership, RoomProperties properties) {
    this.ownership = ownership;
    this.properties = properties;
  }

  @PostConstruct
  void start() {
    ownership.startLiveness();
    // room-lifecycle.md §5.4 / game-host-internals.md §13: platform:sentinel has no TTL, so
    // setIfAbsent here is a no-op on every restart except the very first boot of a fresh
    // datastore — exactly the case the reconciliation sweep's mass-loss check needs (IMP-M2-10).
    ownership.ensureSentinel();
    leaseRenewer.schedule(
        ownership::renewLease, new PeriodicTrigger(properties.lease().renewInterval()));
    ownershipRecheck.schedule(
        ownership::recheckOwnership, new PeriodicTrigger(properties.lease().renewInterval()));
  }

  @PreDestroy
  void stop() {
    leaseRenewer.shutdown();
    ownershipRecheck.shutdown();
  }

  private static ThreadPoolTaskScheduler scheduler(String threadNamePrefix) {
    ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(1);
    scheduler.setThreadNamePrefix(threadNamePrefix);
    scheduler.initialize();
    return scheduler;
  }
}
