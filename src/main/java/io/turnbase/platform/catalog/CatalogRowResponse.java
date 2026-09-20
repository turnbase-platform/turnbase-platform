package io.turnbase.platform.catalog;

import java.time.Instant;
import java.util.UUID;

/** {@code catalog-rest-api.md} §3 — one {@code GET /api/games} row. */
record CatalogRowResponse(
    UUID gameId,
    String title,
    String authorUsername,
    String shortDescription,
    int minPlayers,
    int maxPlayers,
    boolean supportsAi,
    boolean supportsSpectators,
    String version,
    String badge,
    Instant createdAt,
    Instant updatedAt) {}
