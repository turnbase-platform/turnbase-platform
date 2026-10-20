package io.turnbase.platform.websocket;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * {@code IMP-M16-10}: the STOMP channel executors have explicit bounds and give their threads back
 * (load-testing finding 5: 8 + 8 channel threads at the peak of a 32-pair run).
 */
class WebSocketConfigTest {

  @Test
  void channelExecutorIsBoundedAndReleasesIdleThreads() {
    int processors = Runtime.getRuntime().availableProcessors();
    ThreadPoolTaskExecutor executor = WebSocketConfig.channelExecutor("test-");
    executor.initialize();
    try {
      ThreadPoolExecutor pool = executor.getThreadPoolExecutor();
      assertThat(pool.getCorePoolSize()).isEqualTo(processors);
      assertThat(pool.getMaximumPoolSize()).isEqualTo(4 * processors);
      assertThat(pool.getKeepAliveTime(TimeUnit.SECONDS)).isEqualTo(60);
      assertThat(pool.allowsCoreThreadTimeOut()).isTrue();
      assertThat(pool.getQueue().remainingCapacity())
          .isEqualTo(WebSocketConfig.CHANNEL_QUEUE_CAPACITY);
      assertThat(pool.getRejectedExecutionHandler())
          .isInstanceOf(ThreadPoolExecutor.CallerRunsPolicy.class);
    } finally {
      executor.shutdown();
    }
  }
}
