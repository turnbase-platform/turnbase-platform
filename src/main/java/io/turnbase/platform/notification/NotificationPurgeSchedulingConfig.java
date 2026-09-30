package io.turnbase.platform.notification;

import io.turnbase.platform.common.JobsProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.support.PeriodicTrigger;

/**
 * Registers {@link NotificationPurgeJob} and {@link ExpiredSubjectPurgeJob}. The first run of each
 * is held back by {@link JobsProperties#initialDelay} so neither acts inside FR-JOB-008(a)'s
 * startup window.
 */
@Configuration
class NotificationPurgeSchedulingConfig implements SchedulingConfigurer {

  private final NotificationPurgeJob notificationPurge;
  private final ExpiredSubjectPurgeJob expiryPurge;
  private final JobsProperties jobsProperties;

  NotificationPurgeSchedulingConfig(
      NotificationPurgeJob notificationPurge,
      ExpiredSubjectPurgeJob expiryPurge,
      JobsProperties jobsProperties) {
    this.notificationPurge = notificationPurge;
    this.expiryPurge = expiryPurge;
    this.jobsProperties = jobsProperties;
  }

  @Override
  public void configureTasks(ScheduledTaskRegistrar registrar) {
    register(registrar, notificationPurge::run, jobsProperties.notificationPurge());
    register(registrar, expiryPurge::run, jobsProperties.expiryPurge());
  }

  private void register(
      ScheduledTaskRegistrar registrar, Runnable task, JobsProperties.Interval interval) {
    PeriodicTrigger trigger = new PeriodicTrigger(interval.interval());
    trigger.setInitialDelay(jobsProperties.initialDelay(interval));
    registrar.addTriggerTask(task, trigger);
  }
}
