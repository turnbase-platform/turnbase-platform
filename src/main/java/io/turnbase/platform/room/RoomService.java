package io.turnbase.platform.room;

import com.fasterxml.jackson.databind.JsonNode;
import io.turnbase.platform.common.BusinessRuleException;
import io.turnbase.platform.common.NotFoundException;
import io.turnbase.platform.common.PageResponse;
import io.turnbase.platform.common.PaginationMetadata;
import io.turnbase.platform.common.RoomUnavailableRetryException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

/**
 * {@code room-rest-api.md} — the five membership endpoints, orchestrating {@link RoomRepository}'s
 * transactions with {@link RoomOwnershipService}'s claim. Create and get claim after their own
 * (unversioned) work, purely to set the affinity header; join claims first — step 0, {@code
 * IMP-GATE-44} — so its mutation can run fenced and versioned inside {@link RoomEventExecutor}'s
 * lock, per {@code room-lifecycle.md} §2.1's "lock outer → transaction inner → publish after
 * commit" nesting.
 */
@Service
class RoomService {

  /** {@code room-rest-api.md} §7's own worked example: {@code Retry-After: 2}, {@code 2000}ms. */
  private static final long RETRY_AFTER_MS = 2000;

  private final RoomRepository repository;
  private final RoomOwnershipService ownership;
  private final RoomEventExecutor events;
  private final PodIdentity pod;
  private final RoomProperties properties;
  private final GameSessionStarter gameSessionStarter;

  RoomService(
      RoomRepository repository,
      RoomOwnershipService ownership,
      RoomEventExecutor events,
      PodIdentity pod,
      RoomProperties properties,
      GameSessionStarter gameSessionStarter) {
    this.repository = repository;
    this.ownership = ownership;
    this.events = events;
    this.pod = pod;
    this.properties = properties;
    this.gameSessionStarter = gameSessionStarter;
  }

  /** {@code room-rest-api.md} §2. */
  ClaimedResult<RoomResponse> create(UUID creatorId, CreateRoomRequest request) {
    RoomRepository.CreatableGame game =
        repository
            .findCreatableGame(request.gameId(), creatorId)
            .orElseThrow(() -> new NotFoundException("No such game."));
    if (request.spectatingRequested() && !game.supportsSpectators()) {
      throw new BusinessRuleException("This game does not support spectating.");
    }
    UUID roomId = repository.createRoom(creatorId, request, game);
    String header = claimAndGetInstanceHeader(roomId);
    return new ClaimedResult<>(fetchResponse(roomId, creatorId), header);
  }

  /**
   * {@code room-rest-api.md} §5. Claim runs first (step 0, {@code IMP-GATE-44}) so the join
   * transaction — steps 1-6 — can run inside {@link RoomEventExecutor}'s lock, fenced by the epoch
   * that claim just established.
   */
  ClaimedResult<JoinRoomResponse> join(
      UUID userId, UUID roomId, JoinRoomRequest request, @Nullable String inviteCode) {
    String header = claimAndGetInstanceHeader(roomId);
    RoomRepository.JoinOutcome outcome =
        events.execute(
            roomId,
            () -> {
              RoomRepository.JoinOutcome joinOutcome =
                  repository.joinRoom(
                      userId, roomId, request.role(), inviteCode, properties.spectatorCapacity());
              RoomEventExecutor.RoomEvent event =
                  joinOutcome
                      .playerJoined()
                      .map(payload -> new RoomEventExecutor.RoomEvent("PLAYER_JOINED", payload))
                      .orElse(null);
              return new RoomEventExecutor.MutationOutcome<>(joinOutcome, event);
            });
    RoomResponse room = fetchResponse(roomId, userId);
    ReleasedRoomResponse released = outcome.releasedRoom().orElse(null);
    return new ClaimedResult<>(new JoinRoomResponse(room, released), header);
  }

  /**
   * {@code room-rest-api.md} §6, {@code IMP-GATE-44}. Unlike join, a failed claim does not fail the
   * request — this handler may run on an instance that does not own the room (its own text already
   * accepts that); the departure still commits, just without the lock/version/broadcast, deferring
   * the announcement to the owning instance's next sweep.
   */
  void leave(UUID userId, UUID roomId) {
    RoomOwnershipService.ClaimOutcome outcome = ownership.claim(roomId);
    boolean owned =
        outcome instanceof RoomOwnershipService.ClaimOutcome.AlreadyMine
            || outcome instanceof RoomOwnershipService.ClaimOutcome.Adopted;
    if (!owned) {
      repository.leaveRoom(userId, roomId, false);
      return;
    }
    events.execute(
        roomId,
        () -> {
          Optional<RoomRepository.PlayerLeftPayload> left =
              repository.leaveRoom(userId, roomId, true);
          RoomEventExecutor.RoomEvent event =
              left.map(payload -> new RoomEventExecutor.RoomEvent("PLAYER_LEFT", payload))
                  .orElse(null);
          return new RoomEventExecutor.MutationOutcome<>(Boolean.TRUE, event);
        });
  }

  // ---------------------------------------------------------------------
  // Disconnect (IMP-M2-07, room-lifecycle.md §3.1, flows/04-disconnect-and-return.md §A). Called by
  // room.RoomSubscriptionAuthorizer on a genuine SessionDisconnectEvent — one it has already
  // resolved to a (roomId, userId) pair and confirmed is not an instance-initiated close.
  // ---------------------------------------------------------------------

  /**
   * A lost socket always reaches here on the owning instance (subscribe-time ownership verification
   * is the only way a session's room membership is tracked at all) — but ownership can still move
   * between the socket closing and this async event firing, so a lost claim is a quiet no-op: the
   * new owner's adoption (`IMP-GATE-48`) or its own sweep picks the participant up.
   */
  void onDisconnect(UUID roomId, UUID userId) {
    RoomRepository.DisconnectOutcome outcome;
    try {
      outcome =
          events.execute(
              roomId,
              () -> {
                RoomRepository.DisconnectOutcome o =
                    repository.registerDisconnect(roomId, userId, properties.gracePeriod());
                RoomEventExecutor.RoomEvent event =
                    switch (o) {
                      case RoomRepository.DisconnectOutcome.StillConnected ignored -> null;
                      case RoomRepository.DisconnectOutcome.LeftImmediately left ->
                          new RoomEventExecutor.RoomEvent("PLAYER_LEFT", left.payload());
                      case RoomRepository.DisconnectOutcome.GraceOpened opened ->
                          new RoomEventExecutor.RoomEvent("PLAYER_DISCONNECTED", opened.payload());
                    };
                return new RoomEventExecutor.MutationOutcome<>(o, event);
              });
    } catch (RoomUnavailableRetryException e) {
      return;
    }
    if (outcome instanceof RoomRepository.DisconnectOutcome.GraceOpened opened
        && opened.wasHost()) {
      transferHostOnDisconnect(roomId, userId);
    }
  }

  /**
   * FR-HDISC-001, a second fenced mutation after the disconnect's own commit — {@code
   * PLAYER_DISCONNECTED} and {@code HOST_TRANSFERRED} are two distinct events, hence two versions.
   * §3.1's own note: skipped while the migration window is open (FR-HDISC-004 performs it later);
   * skipped with no other human connected (FR-HDISC-003 defers it to the grace period instead).
   */
  private void transferHostOnDisconnect(UUID roomId, UUID disconnectedHostId) {
    if (repository.migrationWindowOpen(roomId)) {
      return;
    }
    Optional<UUID> nextHuman = repository.nextConnectedHumanExcluding(roomId, disconnectedHostId);
    if (nextHuman.isEmpty()) {
      return;
    }
    try {
      events.execute(
          roomId,
          () -> {
            RoomRepository.HostTransferredPayload payload =
                repository.transferHostAutomatically(
                    roomId, nextHuman.get(), disconnectedHostId, "HOST_DISCONNECTED");
            return new RoomEventExecutor.MutationOutcome<>(
                payload, new RoomEventExecutor.RoomEvent("HOST_TRANSFERRED", payload));
          });
    } catch (RoomUnavailableRetryException e) {
      // Ownership moved between the two calls above and this one; the room's next owner (or its
      // own adoption/sweep) is responsible for this room now, not a retry here.
    }
  }

  // ---------------------------------------------------------------------
  // STOMP member commands (room-websocket.md, IMP-M2-05). Every call already runs on the owning
  // pod (subscribe-time re-verification, IMP-M2-02's RoomSubscriptionAuthorizer) — events.execute
  // still resolves the epoch fresh, so a claim lost between subscribe and this command surfaces as
  // ROOM_UNAVAILABLE_RETRY rather than a silent write.
  // ---------------------------------------------------------------------

  RoomRepository.ReadinessChangedPayload toggleReady(UUID roomId, UUID userId, boolean ready) {
    return events.execute(
        roomId,
        () -> {
          RoomRepository.ReadinessChangedPayload payload =
              repository.toggleReady(roomId, userId, ready);
          return new RoomEventExecutor.MutationOutcome<>(
              payload, new RoomEventExecutor.RoomEvent("READINESS_CHANGED", payload));
        });
  }

  RoomRepository.PlayerRoleChangedPayload switchToSpectator(UUID roomId, UUID userId) {
    return events.execute(
        roomId,
        () -> {
          RoomRepository.PlayerRoleChangedPayload payload =
              repository.switchToSpectator(roomId, userId, properties.spectatorCapacity());
          return new RoomEventExecutor.MutationOutcome<>(
              payload, new RoomEventExecutor.RoomEvent("PLAYER_ROLE_CHANGED", payload));
        });
  }

  RoomRepository.PlayerRoleChangedPayload switchToPlayer(UUID roomId, UUID userId) {
    return events.execute(
        roomId,
        () -> {
          RoomRepository.PlayerRoleChangedPayload payload =
              repository.switchToPlayer(roomId, userId);
          return new RoomEventExecutor.MutationOutcome<>(
              payload, new RoomEventExecutor.RoomEvent("PLAYER_ROLE_CHANGED", payload));
        });
  }

  /** {@code REQUEST_ROOM_STATE} — a read, so its {@code MutationOutcome} carries no event. */
  RoomRepository.RoomStateSnapshotWithVersion roomState(UUID roomId, UUID userId) {
    return events.execute(
        roomId,
        () -> {
          if (!repository.isCurrentParticipant(roomId, userId)) {
            throw new RoomCommandException("NOT_MEMBER", "You are not a member of this room.");
          }
          RoomRepository.RoomStateSnapshotWithVersion snapshot =
              repository.roomStateSnapshot(roomId, properties.spectatorCapacity());
          return new RoomEventExecutor.MutationOutcome<>(snapshot, null);
        });
  }

  // ---------------------------------------------------------------------
  // STOMP host commands — IMP-M2-05's share (ADD_TEAM, REMOVE_TEAM, RESIZE_TEAM, REASSIGN_PLAYER,
  // ADD_AI_PLAYER), then IMP-M2-06's (KICK_PLAYER, CHANGE_PARAMETERS, START_GAME, TRANSFER_HOST).
  // ---------------------------------------------------------------------

  RoomRepository.TeamAddedPayload addTeam(
      UUID roomId, UUID hostUserId, String templateName, int configuredSize) {
    return events.execute(
        roomId,
        () -> {
          RoomRepository.TeamAddedPayload payload =
              repository.addTeam(roomId, hostUserId, templateName, configuredSize);
          return new RoomEventExecutor.MutationOutcome<>(
              payload, new RoomEventExecutor.RoomEvent("TEAM_ADDED", payload));
        });
  }

  RoomRepository.TeamRemovedPayload removeTeam(UUID roomId, UUID hostUserId, UUID teamId) {
    return events.execute(
        roomId,
        () -> {
          RoomRepository.TeamRemovedPayload payload =
              repository.removeTeam(roomId, hostUserId, teamId);
          return new RoomEventExecutor.MutationOutcome<>(
              payload, new RoomEventExecutor.RoomEvent("TEAM_REMOVED", payload));
        });
  }

  RoomRepository.TeamResizedPayload resizeTeam(
      UUID roomId, UUID hostUserId, UUID teamId, int newSize) {
    return events.execute(
        roomId,
        () -> {
          RoomRepository.TeamResizedPayload payload =
              repository.resizeTeam(roomId, hostUserId, teamId, newSize);
          return new RoomEventExecutor.MutationOutcome<>(
              payload, new RoomEventExecutor.RoomEvent("TEAM_RESIZED", payload));
        });
  }

  RoomRepository.PlayerReassignedPayload reassignPlayer(
      UUID roomId, UUID hostUserId, UUID playerId, UUID toTeamId) {
    return events.execute(
        roomId,
        () -> {
          RoomRepository.PlayerReassignedPayload payload =
              repository.reassignPlayer(roomId, hostUserId, playerId, toTeamId);
          return new RoomEventExecutor.MutationOutcome<>(
              payload, new RoomEventExecutor.RoomEvent("PLAYER_REASSIGNED", payload));
        });
  }

  /** {@code ADD_AI_PLAYER} — always throws {@code AI_NOT_SUPPORTED} once host is confirmed. */
  void addAiPlayer(UUID roomId, UUID hostUserId) {
    events.execute(
        roomId,
        () -> {
          throw repository.addAiPlayer(roomId, hostUserId);
        });
  }

  RoomRepository.PlayerKickedPayload kickPlayer(UUID roomId, UUID hostUserId, UUID targetPlayerId) {
    return events.execute(
        roomId,
        () -> {
          RoomRepository.PlayerKickedPayload payload =
              repository.kickPlayer(roomId, hostUserId, targetPlayerId);
          return new RoomEventExecutor.MutationOutcome<>(
              payload, new RoomEventExecutor.RoomEvent("PLAYER_KICKED", payload));
        });
  }

  RoomRepository.ParametersChangedPayload changeParameters(
      UUID roomId, UUID hostUserId, Map<String, JsonNode> parameters) {
    return events.execute(
        roomId,
        () -> {
          RoomRepository.ParametersChangedPayload payload =
              repository.changeParameters(roomId, hostUserId, parameters);
          return new RoomEventExecutor.MutationOutcome<>(
              payload, new RoomEventExecutor.RoomEvent("PARAMETERS_CHANGED", payload));
        });
  }

  /**
   * {@code START_GAME} — preconditions only; no event, since nothing about the room changes until
   * {@code IMP-M3-05}'s real {@link GameSessionStarter} exists (`IMP-GATE-46`).
   */
  void startGame(UUID roomId, UUID hostUserId) {
    events.execute(
        roomId,
        () -> {
          repository.checkStartConditions(roomId, hostUserId);
          gameSessionStarter.start(roomId, hostUserId);
          return new RoomEventExecutor.MutationOutcome<>(Boolean.TRUE, null);
        });
  }

  RoomRepository.HostTransferredPayload transferHost(UUID roomId, UUID hostUserId, UUID newHostId) {
    return events.execute(
        roomId,
        () -> {
          RoomRepository.HostTransferredPayload payload =
              repository.transferHost(roomId, hostUserId, newHostId);
          return new RoomEventExecutor.MutationOutcome<>(
              payload, new RoomEventExecutor.RoomEvent("HOST_TRANSFERRED", payload));
        });
  }

  /** {@code room-rest-api.md} §4. */
  ClaimedResult<RoomResponse> get(UUID roomId, UUID viewerId) {
    RoomRepository.RoomResourceRow row =
        repository
            .findVisibleRoomResource(roomId, viewerId)
            .orElseThrow(() -> new NotFoundException("No such room."));
    String header = claimAndGetInstanceHeader(roomId);
    return new ClaimedResult<>(toResponse(row, viewerId), header);
  }

  /**
   * {@code room-rest-api.md} §3.1: 0-or-1 element, resolved via invite code rather than {@code
   * visible()}. The header is {@code null} on the zero-element outcome — there is no room to open a
   * socket for.
   */
  InviteCodeResult resolveInviteCode(String inviteCode, UUID viewerId) {
    var row = repository.resolveInviteCode(inviteCode);
    if (row.isEmpty()) {
      return new InviteCodeResult(
          new PageResponse<>(List.of(), new PaginationMetadata(1, 1, 0, 0)), null);
    }
    String header = claimAndGetInstanceHeader(row.get().roomId());
    RoomResponse response = toResponse(row.get(), viewerId);
    return new InviteCodeResult(
        new PageResponse<>(List.of(response), new PaginationMetadata(1, 1, 1, 1)), header);
  }

  /** {@code room-rest-api.md} §3: the ordinary browsing listing — no affinity header, no claim. */
  PageResponse<RoomListRowResponse> list(UUID viewerId, RoomListFilter filter, Pageable pageable) {
    int spectatorCapacity = properties.spectatorCapacity();
    List<RoomRepository.RoomListRow> rows =
        repository.listRooms(viewerId, filter, spectatorCapacity);
    long total = repository.countRooms(viewerId, filter, spectatorCapacity);
    List<RoomListRowResponse> content = rows.stream().map(this::toListRowResponse).toList();
    return new PageResponse<>(
        content,
        new PaginationMetadata(
            pageable.getPageNumber() + 1,
            pageable.getPageSize(),
            total,
            (int) Math.ceil((double) total / pageable.getPageSize())));
  }

  private RoomResponse fetchResponse(UUID roomId, UUID viewerId) {
    RoomRepository.RoomResourceRow row =
        repository
            .findRoomResource(roomId)
            .orElseThrow(() -> new IllegalStateException("Room vanished within its own request"));
    return toResponse(row, viewerId);
  }

  private RoomResponse toResponse(RoomRepository.RoomResourceRow row, UUID viewerId) {
    boolean isHost = viewerId.equals(row.hostUserId());
    boolean canJoin = canJoin(row);
    boolean canSpectate = canSpectate(row, properties.spectatorCapacity());
    return new RoomResponse(
        row.roomId(),
        row.gameId(),
        row.gameName(),
        row.gameVersion(),
        row.state(),
        RoomVisibility.valueOf(row.visibility()),
        row.hostUserId(),
        row.hostUsername(),
        row.spectatingEnabled(),
        properties.spectatorCapacity(),
        row.spectatorCount(),
        row.playerCount(),
        row.playerCapacity(),
        row.minPlayers(),
        row.maxPlayers(),
        isHost ? row.inviteCode() : null,
        canJoin,
        canSpectate,
        row.version(),
        row.createdAt());
  }

  private RoomListRowResponse toListRowResponse(RoomRepository.RoomListRow row) {
    boolean canJoin =
        row.playerCount() < row.playerCapacity()
            && ("LOBBY".equals(row.state()) || row.supportsMidGameJoin());
    boolean canSpectate =
        row.spectatingEnabled()
            && row.supportsSpectators()
            && row.spectatorCount() < properties.spectatorCapacity();
    return new RoomListRowResponse(
        row.roomId(),
        row.gameId(),
        row.gameName(),
        row.hostUsername(),
        row.state(),
        row.playerCount(),
        row.playerCapacity(),
        row.minPlayers(),
        row.maxPlayers(),
        row.supportsSpectators(),
        row.spectatorCount(),
        canJoin,
        canSpectate);
  }

  private boolean canJoin(RoomRepository.RoomResourceRow row) {
    return row.playerCount() < row.playerCapacity()
        && ("LOBBY".equals(row.state()) || row.supportsMidGameJoin());
  }

  private boolean canSpectate(RoomRepository.RoomResourceRow row, int spectatorCapacity) {
    return row.spectatingEnabled()
        && row.supportsSpectators()
        && row.spectatorCount() < spectatorCapacity;
  }

  /**
   * {@code room-lifecycle.md} §1.2/§1.7: {@link RoomOwnershipService#claimWithRetry} already
   * retries a redirect or a lost adoption race with a bounded backoff; this method only translates
   * final failure into the {@code room-unavailable-retry} {@code 503}. Success always means this
   * pod is the owner — {@code room-rest-api.md} Done-when clause 5 — so the header is always this
   * pod's own id.
   */
  private String claimAndGetInstanceHeader(UUID roomId) {
    RoomOwnershipService.ClaimOutcome outcome = ownership.claimWithRetry(roomId);
    if (outcome instanceof RoomOwnershipService.ClaimOutcome.AlreadyMine
        || outcome instanceof RoomOwnershipService.ClaimOutcome.Adopted) {
      return pod.id();
    }
    throw new RoomUnavailableRetryException(
        "The room's owning instance is not currently reachable. Retry shortly.", RETRY_AFTER_MS);
  }

  /**
   * Every caller other than {@link #resolveInviteCode} always has a room, hence always a header.
   */
  record ClaimedResult<T>(T body, String podInstanceHeader) {}

  /** {@code podInstanceHeader} is {@code null} exactly when {@code body} has zero elements. */
  record InviteCodeResult(PageResponse<RoomResponse> body, @Nullable String podInstanceHeader) {}
}
