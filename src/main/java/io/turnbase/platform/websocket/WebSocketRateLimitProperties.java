package io.turnbase.platform.websocket;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code rate-limiting.md} § Tier 6, {@code turnbase.rate-limit.ws.*} — deliberately its own
 * properties class rather than a member of {@code common.RateLimitProperties}, per that class's own
 * note that Tier 6 is not its concern. {@code capacity} is the burst limit; {@code refillTokens}
 * per {@code refillPeriod} is the sustained rate — the same {@code Bandwidth.classic(capacity,
 * Refill.greedy(...))} shape Bucket4j expects, spelled out as two fields rather than parsed from a
 * single {@code "10/1s"} string.
 *
 * <p>Two-level rule ({@code rate-limiting.md} § Per-mechanism switches): a mechanism acts only when
 * {@code common.RateLimitProperties#enabled()} — the master switch Tier 6 shares with Tiers 1-5 —
 * <em>and</em> its own {@link Switches} flag are both {@code true}. Either may be {@code false}
 * only under the {@code test}, {@code e2e} or {@code loadtest} profile.
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
    Lockout lockout,
    @DefaultValue Switches switches) {

  /**
   * One flag per Tier 6 mechanism, each defaulting to {@code true} so a missing key fails closed.
   */
  public record Switches(
      @DefaultValue("true") boolean connection,
      @DefaultValue("true") boolean snapshot,
      @DefaultValue("true") boolean action,
      @DefaultValue("true") boolean chat,
      @DefaultValue("true") boolean command,
      @DefaultValue("true") boolean subscribe,
      @DefaultValue("true") boolean violationLockout,
      @DefaultValue("true") boolean maxConnectionsPerUser) {

    public static Switches allEnabled() {
      return new Switches(true, true, true, true, true, true, true, true);
    }
  }

  public record Bucket(int capacity, int refillTokens, Duration refillPeriod) {}

  /** Tier 6's own doubling lockout, mirroring Tier 1's shape (`common.RateLimitInterceptor`). */
  public record Lockout(Duration initial, Duration max) {}
}
