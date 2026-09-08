package io.turnbase.platform.account;

import io.turnbase.platform.common.JobsProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.support.PeriodicTrigger;

/**
 * Registers {@link GuestPurgeJob} on {@code turnbase.jobs.guest-cleanup.interval}
 * (local-environment.md §7.2). A {@link SchedulingConfigurer}, not {@code @Scheduled}'s
 * placeholder-string form, because {@link JobsProperties#guestCleanup()} is already a bound {@link
 * java.time.Duration} — {@code @Scheduled(fixedDelayString = ...)} only accepts a plain millisecond
 * count or ISO-8601 text, not the {@code 1h}/{@code 30s} shorthand this document's defaults use,
 * and re-parsing the same value twice in two formats is exactly the kind of split this repo's
 * config surface avoids elsewhere.
 */
@Configuration
class GuestPurgeSchedulingConfig implements SchedulingConfigurer {

  private final GuestPurgeJob job;
  private final JobsProperties jobsProperties;

  GuestPurgeSchedulingConfig(GuestPurgeJob job, JobsProperties jobsProperties) {
    this.job = job;
    this.jobsProperties = jobsProperties;
  }

  @Override
  public void configureTasks(ScheduledTaskRegistrar registrar) {
    registrar.addTriggerTask(
        job::run, new PeriodicTrigger(jobsProperties.guestCleanup().interval()));
  }
}
