package io.turnbase.platform.catalog;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * {@code catalog-rest-api.md} §4. {@code infoDocMarkdown} is raw Markdown source, never rendered
 * server-side (IR-19, {@code input-validation.md} § *The game info document*) — {@code null} when
 * the developer never supplied one, distinct from an empty string, so the client can tell "absent"
 * from "empty" and fall back to {@link #shortDescription} only in the absent case (FR-GDET-003).
 * {@code credits} is the active manifest's optional {@code credits} object, passed through, or
 * {@code null} for an original game.
 */
record GameDetailResponse(
    UUID gameId,
    String title,
    String authorUsername,
    String shortDescription,
    @Nullable String infoDocMarkdown,
    int minPlayers,
    int maxPlayers,
    boolean supportsAi,
    boolean supportsSpectators,
    List<String> tags,
    @Nullable JsonNode credits,
    String version,
    String badge,
    long activePlayerCount,
    long completedSessionCount,
    Instant createdAt,
    Instant updatedAt) {}
