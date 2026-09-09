package io.turnbase.platform.room;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** {@code room-websocket.md} `ROOM_STATE` payload shape, verbatim field-for-field. */
record RoomStateSnapshot(
    UUID roomId,
    String state,
    UUID gameId,
    String gameName,
    boolean spectatingEnabled,
    int spectatorCapacity,
    @Nullable UUID hostId,
    List<PlayerEntry> players,
    List<AiPlayerEntry> aiPlayers,
    List<SpectatorEntry> spectators,
    List<TeamEntry> teams,
    Map<String, Object> parameters,
    List<DisconnectedPlayerEntry> disconnectedPlayers,
    @Nullable UUID sessionId,
    @Nullable String gameFrontendUrl) {

  record PlayerEntry(
      UUID playerId,
      String username,
      @Nullable UUID teamId,
      boolean ready,
      boolean connected,
      long joinOrder) {}

  /** Always empty in v1 (IR-12) — no v1 game may declare {@code capabilities.ai}. */
  record AiPlayerEntry(UUID aiPlayerId, String displayName, UUID teamId) {}

  record SpectatorEntry(UUID playerId, String username) {}

  record TeamEntry(
      UUID teamId, String templateName, int configuredSize, int index, List<UUID> memberIds) {}

  /** Always empty until {@code IMP-M2-07} gives a participant a live grace reservation. */
  record DisconnectedPlayerEntry(UUID playerId, int graceRemainingSeconds) {}
}
