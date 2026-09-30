package io.turnbase.platform.history;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** {@code history-rest-api.md} §4. Empty stats sections are omitted (FR-HIST-022). */
record HistoryEntryResponse(
    UUID gameId,
    String gameName,
    boolean gameLinkable,
    Instant completedAt,
    String outcomeLabel,
    @JsonInclude(JsonInclude.Include.NON_EMPTY) Map<String, Object> playerStats,
    @JsonInclude(JsonInclude.Include.NON_EMPTY) Map<String, Object> teamStats) {}
