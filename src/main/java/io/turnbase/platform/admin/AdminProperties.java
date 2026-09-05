package io.turnbase.platform.admin;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * secrets-and-configuration.md §1 / §7 — Basic-auth credentials for the loopback-bound admin API.
 * Neither field carries a default in {@code application.yml}; an unset env var fails the property
 * placeholder at startup rather than binding an empty string.
 */
@ConfigurationProperties(prefix = "turnbase.admin")
public record AdminProperties(String username, String password) {}
