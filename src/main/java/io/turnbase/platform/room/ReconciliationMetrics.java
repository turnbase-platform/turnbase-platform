package io.turnbase.platform.room;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * {@code room-lifecycle.md} §6 / {@code platform-operations.md} FR-JOB-011 — the reconciliation
 * sweep's own observability surface, named there: per-duty candidate counts, per-duty repair counts
 * (dimensioned by outcome), the two duty-specific gauges, and the abort counter that proves §5.4's
 * reachability guard fires rather than being silently disabled.
 *
 * <p>Also wraps this job's own Redis reads/deletes (duty (b)'s orphaned key-family scan) with
 * {@code conventions.md} §7's {@code turnbase_redis_operation_seconds}/{@code
 * turnbase_redis_errors_total}, tagged {@code concern=game_state} — the concern {@code
 * session:{sessionId}} and its siblings belong to per that document's own mapping.
 */
@Component
class ReconciliationMetrics {

  private final MeterRegistry registry;
  private final Map<String, AtomicLong> candidateGauges = new ConcurrentHashMap<>();
  private final AtomicLong inProgressWithoutSession = new AtomicLong();
  private final AtomicLong teardownOwed = new AtomicLong();
  private final AtomicLong teardownOldestAgeSeconds = new AtomicLong();
  private final Timer redisOperationTimer;
  private final Counter redisErrorCounter;

  ReconciliationMetrics(MeterRegistry registry) {
    this.registry = registry;
    Gauge.builder(
            "turnbase.rooms.in_progress_without_session", inProgressWithoutSession, AtomicLong::get)
        .register(registry);
    Gauge.builder("turnbase.teardown.owed", teardownOwed, AtomicLong::get).register(registry);
    // turnbase_teardown_owed_age_seconds: the TeardownOwed alert fires past 900 (FR-ALERT-007).
    Gauge.builder("turnbase.teardown.owed.age", teardownOldestAgeSeconds, AtomicLong::get)
        .baseUnit("seconds")
        .register(registry);
    this.redisOperationTimer =
        Timer.builder("turnbase_redis_operation_seconds")
            .tag("concern", "game_state")
            .register(registry);
    this.redisErrorCounter =
        Counter.builder("turnbase_redis_errors_total")
            .tag("concern", "game_state")
            .register(registry);
  }

  /** {@code turnbase.reconcile.candidates{duty}} — one gauge per duty, registered on first use. */
  void setCandidates(String duty, long count) {
    candidateGauges.computeIfAbsent(duty, this::registerCandidateGauge).set(count);
  }

  private AtomicLong registerCandidateGauge(String duty) {
    AtomicLong value = new AtomicLong();
    Gauge.builder("turnbase.reconcile.candidates", value, AtomicLong::get)
        .tag("duty", duty)
        .register(registry);
    return value;
  }

  /**
   * {@code turnbase.reconcile.repairs.total{duty,result}} — FR-JOB-011's applied/skipped counts.
   */
  void recordRepair(String duty, String result) {
    Counter.builder("turnbase.reconcile.repairs.total")
        .tag("duty", duty)
        .tag("result", result)
        .register(registry)
        .increment();
  }

  void recordRepair(String duty, String result, long count) {
    Counter.builder("turnbase.reconcile.repairs.total")
        .tag("duty", duty)
        .tag("result", result)
        .register(registry)
        .increment(count);
  }

  void setInProgressWithoutSession(long count) {
    inProgressWithoutSession.set(count);
  }

  void setTeardownOwed(long count, double oldestAgeSeconds) {
    teardownOwed.set(count);
    teardownOldestAgeSeconds.set((long) oldestAgeSeconds);
  }

  /** {@code turnbase.job.aborted.total{job,reason}} — §5.4 guard 1 firing. */
  void recordAborted(String job, String reason) {
    Counter.builder("turnbase.job.aborted.total")
        .tag("job", job)
        .tag("reason", reason)
        .register(registry)
        .increment();
  }

  <T> T recordRedis(Supplier<T> redisOperation) {
    long startNanos = System.nanoTime();
    try {
      return redisOperation.get();
    } catch (RuntimeException e) {
      redisErrorCounter.increment();
      throw e;
    } finally {
      redisOperationTimer.record(Duration.ofNanos(System.nanoTime() - startNanos));
    }
  }
}
