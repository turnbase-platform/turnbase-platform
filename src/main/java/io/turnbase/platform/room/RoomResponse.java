package io.turnbase.platform.room;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * {@code room-rest-api.md} §4 — the full room resource, superset of a list row. {@code viewerRole}
 * is {@code PLAYER}, {@code SPECTATOR} or null.
 */
record RoomResponse(
    UUID roomId,
    UUID gameId,
    String gameName,
    String gameVersion,
    String state,
    RoomVisibility visibility,
    @Nullable UUID hostUserId,
    @Nullable String hostUsername,
    boolean spectatingEnabled,
    int spectatorCapacity,
    long spectatorCount,
    long playerCount,
    long playerCapacity,
    @Nullable Integer minPlayers,
    @Nullable Integer maxPlayers,
    @Nullable String inviteCode,
    boolean canJoin,
    boolean canSpectate,
    @Nullable String viewerRole,
    long version,
    Instant createdAt) {}
