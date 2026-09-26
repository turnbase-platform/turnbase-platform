package io.turnbase.platform.seed;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code local-environment.md} §7.10. {@code bundlesDir} has no default: unset means no game is
 * seeded, which §6 states is a logged no-op and not an error.
 */
@ConfigurationProperties(prefix = "turnbase.seed")
public record SeedProperties(@Nullable String bundlesDir) {}
