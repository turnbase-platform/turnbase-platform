package io.turnbase.platform.game;

import static org.assertj.core.api.Assertions.assertThat;

import io.grpc.ManagedChannel;
import io.turnbase.platform.common.GrpcCorrelationClientInterceptor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * {@code IMP-M16-10}: the gRPC client threads are bounded and released (load-testing finding 5:
 * four {@code grpc-default-worker} threads that never went away).
 */
class GameHostChannelsTest {

  @Test
  void callExecutorIsBoundedAndReleasesIdleThreads() {
    int processors = Runtime.getRuntime().availableProcessors();
    ThreadPoolExecutor executor = GameHostChannels.callExecutor();
    try {
      assertThat(executor.getCorePoolSize()).isEqualTo(processors);
      assertThat(executor.getMaximumPoolSize()).isEqualTo(4 * processors);
      assertThat(executor.getKeepAliveTime(TimeUnit.SECONDS))
          .isEqualTo(GameHostChannels.CALL_EXECUTOR_KEEP_ALIVE_SECONDS);
      assertThat(executor.allowsCoreThreadTimeOut()).isTrue();
      assertThat(executor.getQueue()).isInstanceOf(java.util.concurrent.SynchronousQueue.class);
      assertThat(executor.getRejectedExecutionHandler())
          .isInstanceOf(ThreadPoolExecutor.CallerRunsPolicy.class);
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void manyChannelsShareTheTwoEventLoopThreads() throws Exception {
    GameHostChannels channels = new GameHostChannels(new GrpcCorrelationClientInterceptor());
    try {
      long before = grpcClientThreads();
      for (int i = 0; i < 6; i++) {
        ManagedChannel channel = channels.forAddress("127.0.0.1:" + (50_000 + i));
        channel.getState(true);
      }
      Thread.sleep(300);
      assertThat(grpcClientThreads() - before)
          .isLessThanOrEqualTo(GameHostChannels.EVENT_LOOP_THREADS);
    } finally {
      channels.shutdown();
    }
  }

  private static long grpcClientThreads() {
    return Thread.getAllStackTraces().keySet().stream()
        .filter(t -> t.getName().startsWith("grpc-client-worker-"))
        .count();
  }
}
