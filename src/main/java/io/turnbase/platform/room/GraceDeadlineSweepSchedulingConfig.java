package io.turnbase.platform.room;

import io.turnbase.platform.common.JobsProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.support.PeriodicTrigger;

/**
 * Registers {@link GraceDeadlineSweepJob} on {@code turnbase.jobs.deadline-sweep.interval}
 * (`local-environment.md` §7.2, `room-lifecycle.md` §5.1). No {@code @SchedulerLock}: unlike {@code
 * account.GuestPurgeJob}'s cluster-wide "exactly one instance" job, this sweep is
 * ownership-partitioned — every instance runs it, each sweeping only the rooms it owns
 * (`RoomOwnershipSchedulingConfig`'s own liveness renewal is the precedent for an unlocked,
 * every-instance tick in this package).
 */
@Configuration
class GraceDeadlineSweepSchedulingConfig implements SchedulingConfigurer {

  private final GraceDeadlineSweepJob job;
  private final JobsProperties jobsProperties;

  GraceDeadlineSweepSchedulingConfig(GraceDeadlineSweepJob job, JobsProperties jobsProperties) {
    this.job = job;
    this.jobsProperties = jobsProperties;
  }

  @Override
  public void configureTasks(ScheduledTaskRegistrar registrar) {
    registrar.addTriggerTask(
        job::run, new PeriodicTrigger(jobsProperties.deadlineSweep().interval()));
  }
}
