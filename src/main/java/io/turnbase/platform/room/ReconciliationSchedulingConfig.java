package io.turnbase.platform.room;

import io.turnbase.platform.common.JobsProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.support.PeriodicTrigger;

/**
 * Registers {@link ReconciliationJob} on {@code turnbase.jobs.reconcile.interval}
 * (`local-environment.md` §7.2, `room-lifecycle.md` §5.2). A {@link SchedulingConfigurer}, not
 * {@code @Scheduled}'s placeholder-string form, for the same reason as {@code
 * GraceDeadlineSweepSchedulingConfig}: {@link JobsProperties.Reconcile#interval()} is already a
 * bound {@link java.time.Duration}.
 */
@Configuration
class ReconciliationSchedulingConfig implements SchedulingConfigurer {

  private final ReconciliationJob job;
  private final JobsProperties jobsProperties;

  ReconciliationSchedulingConfig(ReconciliationJob job, JobsProperties jobsProperties) {
    this.job = job;
    this.jobsProperties = jobsProperties;
  }

  @Override
  public void configureTasks(ScheduledTaskRegistrar registrar) {
    // NFR-JOB-002's 90s bound is "one FR-JOB-007 interval + margin" — it assumes the first tick
    // lands at t=interval, not t=0. An immediate first run (PeriodicTrigger's default) would also
    // race duty (e)'s own claim against whichever REST/STOMP caller reaches a fresh room first,
    // for no benefit no document asks for.
    PeriodicTrigger trigger = new PeriodicTrigger(jobsProperties.reconcile().interval());
    trigger.setInitialDelay(jobsProperties.reconcile().interval());
    registrar.addTriggerTask(job::run, trigger);
  }
}
