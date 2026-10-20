package io.turnbase.platform.common;

import org.springframework.boot.task.ThreadPoolTaskSchedulerBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * {@code IMP-M16-10}: the pool the {@code @Scheduled} jobs and the {@code SchedulingConfigurer}
 * jobs run on, sized by {@code spring.task.scheduling.pool.size}.
 *
 * <p>Spring Boot builds this bean itself only when the context has no other {@code TaskScheduler},
 * and the WebSocket broker always contributes one ({@code messageBrokerTaskScheduler}, a pool of
 * one thread per processor). Without this bean the property is silently ignored, the jobs share the
 * broker's pool, and its size depends on the machine. With several {@code TaskScheduler} beans,
 * Spring's scheduling support falls back to the bean named {@code taskScheduler}, which is this
 * one.
 */
@Configuration
class SchedulingPoolConfig {

  @Bean
  ThreadPoolTaskScheduler taskScheduler(ThreadPoolTaskSchedulerBuilder builder) {
    return builder.build();
  }
}
