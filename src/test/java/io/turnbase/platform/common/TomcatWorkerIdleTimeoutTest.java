package io.turnbase.platform.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.TimeUnit;
import org.apache.tomcat.util.threads.TaskQueue;
import org.apache.tomcat.util.threads.ThreadPoolExecutor;
import org.junit.jupiter.api.Test;

/** {@code IMP-M16-10}: Tomcat's workers above {@code min-spare} wait 10 s, not 60 s. */
class TomcatWorkerIdleTimeoutTest {

  @Test
  void shortensTheKeepAliveOfATomcatWorkerPool() {
    TaskQueue queue = new TaskQueue();
    ThreadPoolExecutor pool = new ThreadPoolExecutor(10, 200, 60, TimeUnit.SECONDS, queue);
    queue.setParent(pool);
    try {
      TomcatWorkerIdleTimeout.apply(pool);

      assertThat(pool.getKeepAliveTime(TimeUnit.SECONDS)).isEqualTo(10);
      assertThat(pool.getCorePoolSize()).isEqualTo(10);
      assertThat(pool.getMaximumPoolSize()).isEqualTo(200);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void ignoresAnExecutorThatIsNotTomcats() {
    java.util.concurrent.ThreadPoolExecutor other =
        new java.util.concurrent.ThreadPoolExecutor(
            1, 1, 60, TimeUnit.SECONDS, new java.util.concurrent.LinkedBlockingQueue<>());
    try {
      TomcatWorkerIdleTimeout.apply(other);

      assertThat(other.getKeepAliveTime(TimeUnit.SECONDS)).isEqualTo(60);
    } finally {
      other.shutdownNow();
    }
  }
}
