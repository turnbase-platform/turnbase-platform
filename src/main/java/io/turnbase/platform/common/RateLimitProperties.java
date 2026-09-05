package io.turnbase.platform.common;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * local-environment.md §7.6. Only the two non-tier keys — the full tier budget table lives in
 * {@code rate-limiting.md} and is {@code IMP-M1-02}'s own concern, not this item's.
 */
@ConfigurationProperties(prefix = "turnbase.rate-limit")
public record RateLimitProperties(boolean enabled) {}
