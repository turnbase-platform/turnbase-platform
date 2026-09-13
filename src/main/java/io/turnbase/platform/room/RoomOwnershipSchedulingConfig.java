package io.turnbase.platform.room;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.support.PeriodicTrigger;

/**
 * Writes this instance's liveness key once at startup (before the {@code SET ... XX} renewal loop
 * can run — {@code XX} would never succeed against a key that has never existed) and then registers
 * the {@code turnbase.room.lease.renew-interval} renewal/re-check tick. A {@link
 * SchedulingConfigurer}, not {@code @Scheduled}'s placeholder-string form, for the same reason as
 * {@code account.GuestPurgeSchedulingConfig}: {@link RoomProperties.Lease#renewInterval()} is
 * already a bound {@link java.time.Duration}.
 *
 * <p>No {@code @SchedulerLock}: this must run on **every** instance, not be claimed by one — it is
 * each pod renewing its own liveness key, not a cluster-wide job.
 */
@Configuration
class RoomOwnershipSchedulingConfig implements SchedulingConfigurer {

  private final RoomOwnershipService ownership;
  private final RoomProperties properties;

  RoomOwnershipSchedulingConfig(RoomOwnershipService ownership, RoomProperties properties) {
    this.ownership = ownership;
    this.properties = properties;
  }

  @Override
  public void configureTasks(ScheduledTaskRegistrar registrar) {
    ownership.startLiveness();
    // room-lifecycle.md §5.4 / game-host-internals.md §13: platform:sentinel has no TTL, so
    // setIfAbsent here is a no-op on every restart except the very first boot of a fresh
    // datastore — exactly the case the reconciliation sweep's mass-loss check needs (IMP-M2-10).
    ownership.ensureSentinel();
    registrar.addTriggerTask(
        ownership::renewAndRecheck, new PeriodicTrigger(properties.lease().renewInterval()));
  }
}
