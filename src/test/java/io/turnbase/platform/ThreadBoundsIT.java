package io.turnbase.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.apache.catalina.connector.Connector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.tomcat.TomcatWebServer;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * {@code IMP-M16-10}: the pool settings of the running application, read from the real context —
 * Boot's scheduler, the two STOMP channel executors and the live Tomcat worker pool.
 *
 * <p>Keeps its own boot: it asserts the pools of a freshly started application, which a reused
 * context would not show. (IMP-M20-10)
 *
 * <p>Requirements protected: {@code platform-operations.md} FR-MET-006, NFR-ASC-003.
 */
class ThreadBoundsIT extends PostgresIntegrationTest {

  private ConfigurableApplicationContext context;

  @BeforeEach
  void boot() {
    context =
        SpringApplication.run(
            TurnBaseApplication.class,
            "--server.port=0",
            "--management.server.port=0",
            "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
            "--spring.datasource.username=" + POSTGRES.getUsername(),
            "--spring.datasource.password=" + POSTGRES.getPassword(),
            "--turnbase.artifact-store.endpoint=" + MINIO.getS3URL(),
            "--turnbase.artifact-store.bucket=" + ARTIFACT_BUCKET,
            "--turnbase.artifact-store.access-key=" + MINIO.getUserName(),
            "--turnbase.artifact-store.secret-key=" + MINIO.getPassword(),
            "--turnbase.admin.username=admin",
            "--turnbase.admin.password=admin",
            "--spring.data.redis.url=" + redisUrl());
  }

  @AfterEach
  void stop() {
    context.close();
  }

  @Test
  void scheduledJobsRunOnAPoolOfFourThreads() {
    ThreadPoolTaskScheduler scheduler =
        context.getBean("taskScheduler", ThreadPoolTaskScheduler.class);
    assertThat(scheduler.getScheduledThreadPoolExecutor().getCorePoolSize()).isEqualTo(4);

    ScheduledAnnotationBeanPostProcessor processor =
        context.getBean(ScheduledAnnotationBeanPostProcessor.class);
    ScheduledTaskRegistrar registrar =
        Objects.requireNonNull(
            (ScheduledTaskRegistrar) ReflectionTestUtils.getField(processor, "registrar"));
    TaskScheduler used = Objects.requireNonNull(registrar.getScheduler());
    CompletableFuture<String> thread = new CompletableFuture<>();
    used.schedule(() -> thread.complete(Thread.currentThread().getName()), Instant.now());

    assertThat(thread.orTimeout(10, TimeUnit.SECONDS).join()).startsWith("scheduling-");
  }

  @Test
  void stompChannelExecutorsAreBounded() {
    int processors = Runtime.getRuntime().availableProcessors();
    for (String name :
        new String[] {"clientInboundChannelExecutor", "clientOutboundChannelExecutor"}) {
      ThreadPoolExecutor pool =
          context.getBean(name, ThreadPoolTaskExecutor.class).getThreadPoolExecutor();
      assertThat(pool.getCorePoolSize()).as(name).isEqualTo(processors);
      assertThat(pool.getMaximumPoolSize()).as(name).isEqualTo(4 * processors);
      assertThat(pool.getKeepAliveTime(TimeUnit.SECONDS)).as(name).isEqualTo(60);
      assertThat(pool.allowsCoreThreadTimeOut()).as(name).isTrue();
      assertThat(pool.getQueue().remainingCapacity()).as(name).isZero();
      assertThat(context.getBean(name, ThreadPoolTaskExecutor.class).getThreadNamePrefix())
          .as(name)
          .isEqualTo(name.replace("Executor", "") + "-");
      assertThat(pool.getRejectedExecutionHandler())
          .as(name)
          .isInstanceOf(ThreadPoolExecutor.CallerRunsPolicy.class);
    }
  }

  @Test
  void tomcatWorkerPoolIsBoundedAndShortLived() {
    TomcatWebServer tomcat =
        (TomcatWebServer)
            Objects.requireNonNull(((WebServerApplicationContext) context).getWebServer());
    Connector connector = tomcat.getTomcat().getService().findConnectors()[0];
    org.apache.tomcat.util.threads.ThreadPoolExecutor pool =
        (org.apache.tomcat.util.threads.ThreadPoolExecutor)
            connector.getProtocolHandler().getExecutor();

    assertThat(pool.getCorePoolSize()).isEqualTo(10);
    assertThat(pool.getMaximumPoolSize()).isEqualTo(200);
    assertThat(pool.getKeepAliveTime(TimeUnit.SECONDS)).isEqualTo(10);
  }
}
