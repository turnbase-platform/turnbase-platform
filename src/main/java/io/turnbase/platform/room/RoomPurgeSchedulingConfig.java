package io.turnbase.platform.room;

import io.turnbase.platform.common.JobsProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.support.PeriodicTrigger;

/**
 * Registers {@link RoomPurgeJob} on {@code turnbase.jobs.room-purge.interval}
 * (`local-environment.md` §7.2). A {@link SchedulingConfigurer} for the same reason as every other
 * job config in this package: the interval is already a bound {@link java.time.Duration}.
 */
@Configuration
class RoomPurgeSchedulingConfig implements SchedulingConfigurer {

  private final RoomPurgeJob job;
  private final JobsProperties jobsProperties;

  RoomPurgeSchedulingConfig(RoomPurgeJob job, JobsProperties jobsProperties) {
    this.job = job;
    this.jobsProperties = jobsProperties;
  }

  @Override
  public void configureTasks(ScheduledTaskRegistrar registrar) {
    PeriodicTrigger trigger = new PeriodicTrigger(jobsProperties.roomPurge().interval());
    trigger.setInitialDelay(jobsProperties.initialDelay(jobsProperties.roomPurge()));
    registrar.addTriggerTask(job::run, trigger);
  }
}
