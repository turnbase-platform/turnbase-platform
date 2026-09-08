package io.turnbase.platform.common;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * {@code conventions.md} §7: every Redis operation carries a {@code
 * turnbase_redis_operation_seconds{concern}} timer and a {@code
 * turnbase_redis_errors_total{concern}} counter, tagged at the repository layer. The rate limiter's
 * concern is {@code application} — settled once for every {@code IMP-M1-02} call site rather than
 * re-decided per tier.
 */
@Component
class RateLimitMetrics {

  private final Timer operationTimer;
  private final Counter errorCounter;

  RateLimitMetrics(MeterRegistry meterRegistry) {
    this.operationTimer =
        Timer.builder("turnbase_redis_operation_seconds")
            .tag("concern", "application")
            .register(meterRegistry);
    this.errorCounter =
        Counter.builder("turnbase_redis_errors_total")
            .tag("concern", "application")
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
