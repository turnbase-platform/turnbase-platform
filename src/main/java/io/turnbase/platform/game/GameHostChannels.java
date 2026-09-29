package io.turnbase.platform.game;

import io.grpc.ManagedChannel;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.turnbase.platform.common.GrpcCorrelationClientInterceptor;
import jakarta.annotation.PreDestroy;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/**
 * {@code orchestration-gamehost-protocol.md} *Addressing the Game Host pods*: one gRPC channel per
 * Game Host instance, pooled rather than dialled per call to stay inside the routing-overhead
 * budget, and disposable — a channel that reports {@code UNAVAILABLE} is dropped so the next call
 * re-reads the address from the registry and dials afresh. Keyed by address, not instance id, so a
 * host that re-registers elsewhere never reuses a channel to its old address.
 */
@Component
class GameHostChannels {

  /** The protocol doc's deliberate ceiling (≈16–32 MB); matches the Game Host's own default. */
  static final int MAX_MESSAGE_BYTES = 32 * 1024 * 1024;

  private final Map<String, ManagedChannel> channels = new ConcurrentHashMap<>();
  private final GrpcCorrelationClientInterceptor correlation;

  GameHostChannels(GrpcCorrelationClientInterceptor correlation) {
    this.correlation = correlation;
  }

  ManagedChannel forAddress(String address) {
    return channels.computeIfAbsent(
        address,
        a ->
            NettyChannelBuilder.forTarget(a)
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
  }
}
