package io.turnbase.platform.session;

import java.time.Duration;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * local-environment.md §7.4. {@code jwtKeySet} is the raw {@code TURNBASE_JWT_KEY_SET} JSON
 * (secrets-and-configuration.md §3.1) — {@code @Nullable} because it is only required when {@code
 * jwtKeySetRequired} is {@code true} (IMP-GATE-16); {@link JwtKeySetConfig} enforces that.
 */
@ConfigurationProperties(prefix = "turnbase.auth")
public record AuthProperties(
    Duration sessionTtl, boolean jwtKeySetRequired, @Nullable String jwtKeySet, OAuth oauth) {

  /** {@code oauth-rest-api.md} §9 — the two OAuth round-trip windows. */
  public record OAuth(Duration stateTtl, Duration pendingRegistrationTtl) {}
}
