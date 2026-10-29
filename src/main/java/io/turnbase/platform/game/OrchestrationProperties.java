package io.turnbase.platform.game;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code local-environment.md} §7.8 — the monolith's side of the liveness contract. {@code
 * heartbeatInterval} and {@code leaseTtl} travel to the Game Host in {@code RegisterResponse}
 * rather than being configured there, so the two cannot be set inconsistently; {@code
 * game-host-internals.md} §11 requires the TTL to be at least twice the interval. {@code
 * maxSessionsPerHost} is FR-SCALE-005's per-host cap: a host at it is not selected for a new
 * session.
 */
@ConfigurationProperties(prefix = "turnbase.orchestration")
public record OrchestrationProperties(
    Duration heartbeatInterval,
    Duration leaseTtl,
    Duration dispatchDeadline,
    @DefaultValue("40s") Duration hostWait,
    @DefaultValue("60s") Duration quarantineTtl,
    @DefaultValue("100") int maxSessionsPerHost) {

  public OrchestrationProperties {
    if (maxSessionsPerHost < 1) {
      throw new IllegalArgumentException(
          "turnbase.orchestration.max-sessions-per-host must be at least 1");
    }
    if (leaseTtl.compareTo(heartbeatInterval.multipliedBy(2)) < 0) {
      throw new IllegalArgumentException(
          "turnbase.orchestration.lease-ttl must be at least twice heartbeat-interval");
    }
  }
}
