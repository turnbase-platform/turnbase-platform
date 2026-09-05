package io.turnbase.platform.common;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * local-environment.md §7.7 — the platform's own origin, checked against {@link
 * GameFrontendProperties}.
 */
@ConfigurationProperties(prefix = "turnbase.platform")
public record PlatformOriginProperties(String origin) {}
