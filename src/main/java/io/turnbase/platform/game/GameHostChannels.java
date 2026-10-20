package io.turnbase.platform.game;

import io.grpc.ManagedChannel;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.netty.channel.EventLoopGroup;
import io.grpc.netty.shaded.io.netty.channel.nio.NioEventLoopGroup;
import io.grpc.netty.shaded.io.netty.channel.socket.nio.NioSocketChannel;
import io.turnbase.platform.common.GrpcCorrelationClientInterceptor;
import jakarta.annotation.PreDestroy;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/**
 * {@code orchestration-gamehost-protocol.md} *Addressing the Game Host pods*: one gRPC channel per
 * Game Host instance, pooled rather than dialled per call to stay inside the routing-overhead
 * budget, and disposable — a channel that reports {@code UNAVAILABLE} is dropped so the next call
 * re-reads the address from the registry and dials afresh. Keyed by address, not instance id, so a
 * host that re-registers elsewhere never reuses a channel to its old address.
 *
 * <p>{@code IMP-M16-10}: every channel shares one two-thread event loop and one bounded call
 * executor. gRPC's own defaults are a lazily grown event loop of twice the processors that never
 * shrinks, and an unbounded cached pool.
 */
@Component
class GameHostChannels {

  /** The protocol doc's deliberate ceiling (≈16–32 MB); matches the Game Host's own default. */
  static final int MAX_MESSAGE_BYTES = 32 * 1024 * 1024;

  /** Event-loop threads shared by every channel. */
  static final int EVENT_LOOP_THREADS = 2;

  /** Idle seconds after which a call-executor thread is released, core threads included. */
  static final int CALL_EXECUTOR_KEEP_ALIVE_SECONDS = 60;

  private final Map<String, ManagedChannel> channels = new ConcurrentHashMap<>();
  private final GrpcCorrelationClientInterceptor correlation;
  private final EventLoopGroup eventLoop =
      new NioEventLoopGroup(EVENT_LOOP_THREADS, daemonThreads("grpc-client-worker-"));
  private final ThreadPoolExecutor callExecutor = callExecutor();

  GameHostChannels(GrpcCorrelationClientInterceptor correlation) {
    this.correlation = correlation;
  }

  /**
   * Core = available processors, maximum four times that, a hand-off instead of a queue (so the
   * pool grows before anything waits), caller-runs overflow.
   */
  static ThreadPoolExecutor callExecutor() {
    int processors = Runtime.getRuntime().availableProcessors();
    ThreadPoolExecutor executor =
        new ThreadPoolExecutor(
            processors,
            4 * processors,
            CALL_EXECUTOR_KEEP_ALIVE_SECONDS,
            TimeUnit.SECONDS,
            new SynchronousQueue<>(),
            daemonThreads("grpc-client-call-"),
            new ThreadPoolExecutor.CallerRunsPolicy());
    executor.allowCoreThreadTimeOut(true);
    return executor;
  }

  private static ThreadFactory daemonThreads(String prefix) {
    AtomicInteger counter = new AtomicInteger();
    return runnable -> {
      Thread thread = new Thread(runnable, prefix + counter.incrementAndGet());
      thread.setDaemon(true);
      return thread;
    };
  }

  ManagedChannel forAddress(String address) {
    return channels.computeIfAbsent(
        address,
        a ->
            NettyChannelBuilder.forTarget(a)
                .channelType(NioSocketChannel.class)
                .eventLoopGroup(eventLoop)
                .executor(callExecutor)
                .usePlaintext()
                .maxInboundMessageSize(MAX_MESSAGE_BYTES)
                .intercept(correlation)
                .build());
  }

  void discard(String address) {
    ManagedChannel channel = channels.remove(address);
    if (channel != null) {
      channel.shutdownNow();
    }
  }

  @PreDestroy
  void shutdown() throws InterruptedException {
    for (ManagedChannel channel : channels.values()) {
      channel.shutdown();
    }
    for (ManagedChannel channel : channels.values()) {
      channel.awaitTermination(2, TimeUnit.SECONDS);
    }
    callExecutor.shutdown();
    eventLoop.shutdownGracefully(0, 2, TimeUnit.SECONDS);
  }
}
