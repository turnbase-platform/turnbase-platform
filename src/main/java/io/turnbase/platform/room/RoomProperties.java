package io.turnbase.platform.room;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * local-environment.md §7.1. The three {@code lease} values are a set, not three independent knobs
 * — {@code renewInterval ≪ ttl} and {@code selfEvictAfter < ttl} with a guard band must hold
 * together (room-lifecycle.md OQ-001); changing one without the others is how ownership-fencing
 * breaks.
 */
@ConfigurationProperties(prefix = "turnbase.room")
public record RoomProperties(
    Duration gracePeriod,
    Duration migrationWindow,
    Duration idleWindow,
    int spectatorCapacity,
    Lease lease) {

  public record Lease(Duration ttl, Duration renewInterval, Duration selfEvictAfter) {}
}
