package io.turnbase.platform.websocket;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code rate-limiting.md} § Tier 6, {@code turnbase.rate-limit.ws.*} — deliberately its own
 * properties class rather than a member of {@code common.RateLimitProperties}, per that class's own
 * note that Tier 6 is not its concern. {@code capacity} is the burst limit; {@code refillTokens}
 * per {@code refillPeriod} is the sustained rate — the same {@code Bandwidth.classic(capacity,
 * Refill.greedy(...))} shape Bucket4j expects, spelled out as two fields rather than parsed from a
 * single {@code "10/1s"} string.
 *
 * <p>Governed by {@code common.RateLimitProperties#enabled()} — Tier 6 shares that single top-level
 * kill switch rather than carrying a second one, per {@code rate-limiting.md} § Configuration's
 * rule that the escape hatch is one flag, gated to the {@code test}/{@code e2e} profiles.
 */
@ConfigurationProperties(prefix = "turnbase.rate-limit.ws")
public record WebSocketRateLimitProperties(
    Bucket connection,
    Bucket snapshot,
    Bucket action,
    Bucket chat,
    Bucket command,
    Bucket subscribe,
    int maxConnectionsPerUser,
    int violationsBeforeClose,
    Duration violationWindow,
    Lockout lockout) {

  public record Bucket(int capacity, int refillTokens, Duration refillPeriod) {}

  /** Tier 6's own doubling lockout, mirroring Tier 1's shape (`common.RateLimitInterceptor`). */
  public record Lockout(Duration initial, Duration max) {}
}
