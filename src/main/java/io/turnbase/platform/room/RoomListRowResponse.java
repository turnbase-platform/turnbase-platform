package io.turnbase.platform.room;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** {@code room-rest-api.md} §3 — one row of {@code GET /api/rooms}. */
record RoomListRowResponse(
    UUID roomId,
    UUID gameId,
    String gameName,
    @Nullable String hostUsername,
    String state,
    long playerCount,
    long playerCapacity,
    @Nullable Integer minPlayers,
    @Nullable Integer maxPlayers,
    boolean spectatingSupported,
    long spectatorCount,
    boolean canJoin,
    boolean canSpectate) {}
