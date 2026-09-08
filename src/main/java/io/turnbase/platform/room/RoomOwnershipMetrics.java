package io.turnbase.platform.room;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * {@code conventions.md} §7: every Redis operation carries a {@code
 * turnbase_redis_operation_seconds{concern}} timer and a {@code
 * turnbase_redis_errors_total{concern}} counter, tagged at the repository layer. {@code
 * IMP-M2-03}'s lease and claim/release scripts are the {@code ownership} concern — a separate
 * wrapper from {@code common.RateLimitMetrics} ({@code application}), since each concern is its own
 * tag value, settled once in {@code conventions.md} rather than re-decided per call site.
 */
@Component
class RoomOwnershipMetrics {

  private final Timer operationTimer;
  private final Counter errorCounter;

  RoomOwnershipMetrics(MeterRegistry meterRegistry) {
    this.operationTimer =
        Timer.builder("turnbase_redis_operation_seconds")
            .tag("concern", "ownership")
            .register(meterRegistry);
    this.errorCounter =
        Counter.builder("turnbase_redis_errors_total")
            .tag("concern", "ownership")
            .register(meterRegistry);
  }

  <T> T record(Supplier<T> redisOperation) {
    long startNanos = System.nanoTime();
    try {
      return redisOperation.get();
    } catch (RuntimeException e) {
      errorCounter.increment();
      throw e;
    } finally {
      operationTimer.record(Duration.ofNanos(System.nanoTime() - startNanos));
    }
  }
}
