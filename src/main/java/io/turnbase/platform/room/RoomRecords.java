package io.turnbase.platform.room;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The value types shared by the room repositories and their callers (split from {@code
 * RoomRepository}, {@code IMP-M20-14}).
 */
final class RoomRecords {

  private RoomRecords() {}

  /** {@link #createRoom}'s outcome: the new room and the one the creator implicitly left. */
  record CreatedRoom(UUID roomId, Optional<ReleasedRoomResponse> releasedRoom) {}

  record JoinPlan(boolean alreadyParticipant, @Nullable UUID teamId, String callerUsername) {}

  record ChatParticipantRow(
      String role, @Nullable UUID teamId, String username, boolean teamChatAvailable) {}

  /** Published when a {@code player_left} row is written; handled after the transaction commits. */
  record PlayerLeftQueued(UUID sessionId) {}

  record PlayTarget(UUID gameId, String version, String frontendEntryPoint) {}

  record TargetMember(String role, @Nullable UUID teamId) {}

  record RoomParametersRow(@Nullable String parametersJson) {}

  record TeamOccupancyRow(UUID teamId, int templateIndex, long occupied) {}

  /** {@code manifest-schema.md} § `parameters` — one entry's declared schema. */
  /**
   * {@code min}/{@code max} are primitive: manifest-schema.md requires them on every {@code
   * integer}/{@code decimal} parameter, the only types that read them, so there is no null case to
   * represent.
   */
  record ParameterSchema(
      String key,
      String type,
      double min,
      double max,
      @Nullable Integer maxLength,
      @Nullable List<String> options) {}

  /** {@code room-websocket.md} `PLAYER_KICKED` payload shape. */
  record PlayerKickedPayload(UUID playerId, UUID byHostId) {}

  /** {@code room-websocket.md} `PARAMETERS_CHANGED` payload shape. */
  record ParametersChangedPayload(Map<String, Object> parameters) {}

  /** {@code room-websocket.md} `HOST_TRANSFERRED` payload shape. */
  record HostTransferredPayload(UUID newHostId, @Nullable UUID previousHostId, String reason) {}

  record MemberForCommand(
      String role,
      @Nullable UUID hostUserId,
      String state,
      boolean spectatingEnabled,
      boolean supportsSpectators) {}

  record MemberAndState(String role, String state) {}

  record RoomHostAndState(@Nullable UUID hostUserId, String state) {}

  record ParticipantRow(
      UUID userId,
      String username,
      String role,
      @Nullable UUID teamId,
      boolean ready,
      long joinOrder,
      boolean connected,
      @Nullable Long graceRemainingSeconds) {}

  record RoomSnapshotHeader(
      UUID roomId,
      String state,
      UUID gameId,
      String gameName,
      boolean spectatingEnabled,
      @Nullable UUID hostUserId,
      @Nullable String parametersJson,
      @Nullable UUID activeSessionId,
      long version) {}

  /** {@code version} at the envelope level, {@code snapshot} as {@code ROOM_STATE}'s payload. */
  record RoomStateSnapshotWithVersion(long version, RoomStateSnapshot snapshot) {}

  /** {@code room-websocket.md} `READINESS_CHANGED` payload shape. */
  record ReadinessChangedPayload(UUID playerId, boolean ready) {}

  /** {@code room-websocket.md} `PLAYER_ROLE_CHANGED` payload shape. */
  record PlayerRoleChangedPayload(
      UUID playerId, String newRole, @Nullable UUID teamId, boolean readinessCleared) {}

  /** {@code room-websocket.md} `TEAM_ADDED` payload shape. */
  record TeamAddedPayload(UUID teamId, String templateName, int configuredSize, int index) {}

  /** {@code room-websocket.md} `TEAM_REMOVED` payload shape. */
  record TeamRemovedPayload(UUID teamId) {}

  /** {@code room-websocket.md} `TEAM_RESIZED` payload shape. */
  record TeamResizedPayload(UUID teamId, int newConfiguredSize) {}

  /** {@code room-websocket.md} `PLAYER_REASSIGNED` payload shape. */
  record PlayerReassignedPayload(
      UUID playerId, UUID fromTeamId, UUID toTeamId, boolean readinessReset) {}

  record WhereClause(String sql, Map<String, Object> params) {}

  record TeamTemplateInfo(String name, int sizeMin, int sizeMax, int countMin, int countMax) {}

  record CreatableGameCandidate(
      CreatableGame game,
      @Nullable UUID ownerUserId,
      boolean deleted,
      boolean archived,
      boolean listed,
      String badge) {

    /** {@code data-model-catalog.md} §1 — FR-GAME-013, FR-GAME-007, FR-PRV-002. */
    boolean isCreatableBy(UUID creatorId) {
      boolean isOwner = creatorId.equals(ownerUserId);
      return !deleted
          && !archived
          && (listed || isOwner)
          && (!"UNVERIFIED".equals(badge) || isOwner);
    }
  }

  record CreatableGame(
      UUID gameId,
      String title,
      String version,
      @Nullable String manifest,
      @Nullable Integer minPlayers,
      @Nullable Integer maxPlayers,
      boolean supportsSpectators,
      boolean supportsMidGameJoin) {}

  record RoomForJoin(
      UUID roomId,
      String visibility,
      String state,
      boolean spectatingEnabled,
      @Nullable UUID hostUserId,
      String inviteCode,
      boolean supportsSpectators,
      boolean supportsMidGameJoin,
      String callerUsername) {}

  record RoomResourceRow(
      UUID roomId,
      UUID gameId,
      String gameName,
      String gameVersion,
      String state,
      String visibility,
      @Nullable UUID hostUserId,
      @Nullable String hostUsername,
      boolean spectatingEnabled,
      String inviteCode,
      long version,
      Instant createdAt,
      @Nullable Integer minPlayers,
      @Nullable Integer maxPlayers,
      boolean supportsSpectators,
      boolean supportsMidGameJoin,
      long playerCount,
      long playerCapacity,
      long spectatorCount) {}

  record RoomListRow(
      UUID roomId,
      UUID gameId,
      String gameName,
      @Nullable String hostUsername,
      String state,
      long playerCount,
      long playerCapacity,
      @Nullable Integer minPlayers,
      @Nullable Integer maxPlayers,
      boolean spectatingEnabled,
      boolean supportsSpectators,
      boolean supportsMidGameJoin,
      long spectatorCount) {}

  record JoinOutcome(
      UUID roomId,
      Optional<ReleasedRoomResponse> releasedRoom,
      Optional<PlayerJoinedPayload> playerJoined,
      Optional<RoomInvitationRepository.ResolvedInvitation> acceptedInvitation) {}

  /** {@code room-websocket.md} `PLAYER_JOINED` payload shape, verbatim field-for-field. */
  record PlayerJoinedPayload(
      UUID playerId, String username, String role, @Nullable UUID teamId, long joinOrder) {}

  /** {@code room-websocket.md} `PLAYER_LEFT` payload shape. */
  record PlayerLeftPayload(UUID playerId, String role) {}

  sealed interface DisconnectOutcome {
    record StillConnected() implements DisconnectOutcome {}

    record LeftImmediately(PlayerLeftPayload payload) implements DisconnectOutcome {}

    record GraceOpened(PlayerDisconnectedPayload payload, boolean wasHost)
        implements DisconnectOutcome {}
  }

  /** {@code room-websocket.md} `PLAYER_DISCONNECTED` payload shape. */
  record PlayerDisconnectedPayload(UUID playerId, long gracePeriodSeconds) {}

  sealed interface ReconnectOutcome {
    record Reconnected(PlayerReconnectedPayload payload) implements ReconnectOutcome {}

    record OrdinaryConnect() implements ReconnectOutcome {}
  }

  /** {@code room-websocket.md} `PLAYER_RECONNECTED` payload shape, verbatim field-for-field. */
  record PlayerReconnectedPayload(UUID playerId) {}

  record DueReservation(
      UUID roomId,
      UUID userId,
      UUID reservationId,
      @Nullable UUID teamId,
      @Nullable UUID activeSessionId) {}

  record ResolvedReservation(@Nullable UUID teamId) {}

  /** {@code room-websocket.md} `PLAYER_LOST` payload shape. */
  record PlayerLostPayload(UUID playerId, @Nullable UUID teamId, String reason) {}

  /** {@code true} iff this call actually performed the detach (step 2's idempotency check). */
  record DetachOutcome(boolean detached) {}

  /** {@code room-websocket.md} `ROOM_CLOSED` payload shape. */
  record RoomClosedPayload(String reason) {}

  /** {@code room-websocket.md} `GAME_ENDED` payload shape, shared with FR-JOB-007c's trigger. */
  record GameEndedPayload(UUID roomId, boolean readinessReset) {}

  record DeferredTail(
      UUID roomId,
      UUID userId,
      @Nullable UUID teamId,
      String leaveReason,
      @Nullable UUID hostUserId) {}

  record OutboxRow(long id, UUID sessionId, @Nullable Long sequence, String payloadJson) {}

  record SessionRoomRow(UUID roomId, String state, SessionRoomView.Member member) {}

  record SessionParticipant(UUID userId, String role, @Nullable UUID teamId) {}

  record ActingParticipant(String role, @Nullable UUID teamId, String username) {}

  record DueTeardown(UUID sessionId, UUID roomId, String reason) {}

  record RoomSessionPair(UUID roomId, UUID sessionId) {}
}
