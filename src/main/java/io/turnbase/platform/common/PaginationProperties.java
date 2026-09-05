package io.turnbase.platform.common;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** local-environment.md §7.4 — shared across every list endpoint (catalog, history, social). */
@ConfigurationProperties(prefix = "turnbase.pagination")
public record PaginationProperties(int defaultPageSize, int maxPageSize) {}
