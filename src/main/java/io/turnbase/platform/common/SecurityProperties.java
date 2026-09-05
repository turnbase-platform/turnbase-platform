package io.turnbase.platform.common;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** local-environment.md §7.6 — CIDR ranges trusted for client-IP derivation ahead of the proxy. */
@ConfigurationProperties(prefix = "turnbase.security")
public record SecurityProperties(List<String> trustedProxies) {}
