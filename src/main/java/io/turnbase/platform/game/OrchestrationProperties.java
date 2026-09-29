package io.turnbase.platform.game;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code local-environment.md} §7.8 — the monolith's side of the liveness contract. {@code
 * heartbeatInterval} and {@code leaseTtl} travel to the Game Host in {@code RegisterResponse}
 * rather than being configured there, so the two cannot be set inconsistently; {@code
 * game-host-internals.md} §11 requires the TTL to be at least twice the interval.
 */
@ConfigurationProperties(prefix = "turnbase.orchestration")
public record OrchestrationProperties(
    Duration heartbeatInterval, Duration leaseTtl, Duration dispatchDeadline) {

  public OrchestrationProperties {
    if (leaseTtl.compareTo(heartbeatInterval.multipliedBy(2)) < 0) {
      throw new IllegalArgumentException(
          "turnbase.orchestration.lease-ttl must be at least twice heartbeat-interval");
    }
  }
}
