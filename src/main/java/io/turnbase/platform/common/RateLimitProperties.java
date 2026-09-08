package io.turnbase.platform.common;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.Name;

/**
 * local-environment.md §7.6 plus {@code rate-limiting.md} § Configuration's Tiers 1-5 budgets —
 * Tier 6 (WebSocket frames) is {@code IMP-M2-02}'s own {@code turnbase.rate-limit.ws.*} keys, not
 * this item's.
 */
@ConfigurationProperties(prefix = "turnbase.rate-limit")
public record RateLimitProperties(
    boolean enabled,
    Auth auth,
    Bucket api,
    Bucket upload,
    @Name("public") Bucket publicTier,
    Minting minting) {

  /** Tier 1 — failure-only counting, doubling lockout. */
  public record Auth(int failuresBeforeLockout, Duration initialLockout) {}

  /** A plain capacity/window bucket — Tiers 2, 3 and 4 all share this shape. */
  public record Bucket(int capacity, Duration window) {}

  /** Tier 5 — one independent success-counting bucket per minting endpoint. */
  public record Minting(Bucket guest, Bucket registration) {}
}
