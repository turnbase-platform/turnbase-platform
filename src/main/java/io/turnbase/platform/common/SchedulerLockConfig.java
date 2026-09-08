package io.turnbase.platform.common;

import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * FR-JOB-005's "exactly one instance per interval" over the {@code shedlock} table the baseline
 * migration already carries ({@code schema-migrations.md} §5). {@code @EnableScheduling} itself is
 * already declared on {@link BusinessMetricsConfig}; only the lock provider is new here.
 */
@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "PT30M")
class SchedulerLockConfig {

  @Bean
  LockProvider lockProvider(DataSource dataSource) {
    return new JdbcTemplateLockProvider(dataSource);
  }
}
