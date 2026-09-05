package io.turnbase.platform.common;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * local-environment.md §7.7. {@code originTemplate} carries a literal {@code {gameId}} placeholder
 * — never constructed client-side (ADR-0007 §3), only ever expanded server-side into an absolute
 * {@code gameFrontendUrl}.
 */
@ConfigurationProperties(prefix = "turnbase.game-frontend")
public record GameFrontendProperties(String originTemplate) {}
