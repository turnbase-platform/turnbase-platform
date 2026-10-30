package io.turnbase.platform.game;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.function.ToDoubleFunction;
import org.springframework.stereotype.Component;

/**
 * The Game Host fleet's load as gauges, read from the registry at scrape time so that every
 * Platform replica reports the same figures. KEDA scales the Game Host on them ({@code
 * load-testing-phase-b.md} §5.3, FR-SCALE-003/-005): the Game Host's own pods cannot be scraped as
 * one target behind a Service.
 */
@Component
class GameHostLoadMetrics {

  private final HostRegistry registry;
  private final OrchestrationProperties properties;

  GameHostLoadMetrics(
      HostRegistry registry, OrchestrationProperties properties, MeterRegistry meters) {
    this.registry = registry;
    this.properties = properties;
    gauge(meters, "turnbase.gamehost.live", "Live Game Hosts not draining.", this::liveCount);
    gauge(
        meters,
        "turnbase.gamehost.sessions.active",
        "Active sessions summed over live Game Hosts.",
        loads -> loads.stream().mapToInt(HostRegistry.HostLoad::activeSessions).sum());
    gauge(
        meters,
        "turnbase.gamehost.sessions.utilization",
        "Active sessions of non-draining hosts over their count times the per-host cap (can exceed 1).",
        this::utilization);
    gauge(
        meters,
        "turnbase.gamehost.memory.max.percent",
        "Highest heap use percentage over live Game Hosts.",
        loads -> loads.stream().mapToInt(HostRegistry.HostLoad::memoryPercent).max().orElse(0));
  }

  private void gauge(
      MeterRegistry meters,
      String name,
      String description,
      ToDoubleFunction<List<HostRegistry.HostLoad>> value) {
    Gauge.builder(name, this, m -> value.applyAsDouble(m.registry.loads()))
        .description(description)
        .register(meters);
  }

  private double liveCount(List<HostRegistry.HostLoad> loads) {
    return loads.stream().filter(l -> !l.draining()).count();
  }

  private double utilization(List<HostRegistry.HostLoad> loads) {
    double capacity = liveCount(loads) * properties.maxSessionsPerHost();
    // A draining host's sessions are on their way out; counting them would scale out during a
    // scale-in.
    int sessions =
        loads.stream()
            .filter(l -> !l.draining())
            .mapToInt(HostRegistry.HostLoad::activeSessions)
            .sum();
    return capacity == 0 ? 0 : sessions / capacity;
  }
}
