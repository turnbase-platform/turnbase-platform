package io.turnbase.platform.upload;

import io.turnbase.platform.common.JobsProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.support.PeriodicTrigger;

/**
 * Registers {@link UploadSlotCleanupJob} on {@code turnbase.jobs.upload-slot-cleanup.interval}
 * (default 15m, {@code local-environment.md} §7.2) — {@code room.RoomPurgeSchedulingConfig}'s
 * identical shape.
 */
@Configuration
class UploadSlotCleanupSchedulingConfig implements SchedulingConfigurer {

  private final UploadSlotCleanupJob job;
  private final JobsProperties jobsProperties;

  UploadSlotCleanupSchedulingConfig(UploadSlotCleanupJob job, JobsProperties jobsProperties) {
    this.job = job;
    this.jobsProperties = jobsProperties;
  }

  @Override
  public void configureTasks(ScheduledTaskRegistrar registrar) {
    PeriodicTrigger trigger = new PeriodicTrigger(jobsProperties.uploadSlotCleanup().interval());
    trigger.setInitialDelay(jobsProperties.uploadSlotCleanup().interval());
    registrar.addTriggerTask(job::run, trigger);
  }
}
