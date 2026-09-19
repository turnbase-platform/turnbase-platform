package io.turnbase.platform.upload;

import java.util.concurrent.Executor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * {@code bundle-format.md} §3.1: Phases C-G run in a background job after {@code confirm} returns
 * {@code 202}, so the request thread is never blocked on fetching, inflating and analyzing up to a
 * 1 GB archive. No async executor exists elsewhere in this repository yet (IMP-M4-03 is the first
 * {@code @Async} call site), so this is a small, dedicated, bounded pool rather than Spring Boot's
 * default {@code SimpleAsyncTaskExecutor} (unbounded, one thread per task).
 */
@Configuration
@EnableAsync
class UploadAsyncConfig {

  static final String EXECUTOR_BEAN_NAME = "uploadValidationExecutor";

  @Bean(EXECUTOR_BEAN_NAME)
  Executor uploadValidationExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(2);
    executor.setMaxPoolSize(8);
    executor.setQueueCapacity(50);
    executor.setThreadNamePrefix("upload-validation-");
    executor.initialize();
    return executor;
  }
}
