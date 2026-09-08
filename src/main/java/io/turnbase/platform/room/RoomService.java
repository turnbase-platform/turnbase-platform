package io.turnbase.platform.room;

import io.turnbase.platform.common.BusinessRuleException;
import io.turnbase.platform.common.NotFoundException;
import io.turnbase.platform.common.PageResponse;
import io.turnbase.platform.common.PaginationMetadata;
import io.turnbase.platform.common.RoomUnavailableRetryException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

/**
 * {@code room-rest-api.md} — the five membership endpoints, orchestrating {@link RoomRepository}'s
 * transactions with {@link RoomOwnershipService}'s claim (run *after* commit, per {@code
 * room-lifecycle.md} §4's "no remote call inside a transaction" rule and §5 step 7's own ordering).
 */
@Service
class RoomService {

  /**
   * {@code room-lifecycle.md} §1.7: "the handler retries internally for a few hundred milliseconds
   * and then gives up." Three attempts, 100ms apart — two sleeps, ~200ms of held request-thread
   * time, well inside the stated budget and short enough that a rolling deploy's simultaneous
   * reconnect storm cannot exhaust the thread pool.
   */
  private static final int CLAIM_ATTEMPTS = 3;

  private static final Duration CLAIM_RETRY_DELAY = Duration.ofMillis(100);

  /** {@code room-rest-api.md} §7's own worked example: {@code Retry-After: 2}, {@code 2000}ms. */
  private static final long RETRY_AFTER_MS = 2000;

  private final RoomRepository repository;
  private final RoomOwnershipService ownership;
  private final PodIdentity pod;
  private final RoomProperties properties;

  RoomService(
      RoomRepository repository,
      RoomOwnershipService ownership,
      PodIdentity pod,
      RoomProperties properties) {
    this.repository = repository;
    this.ownership = ownership;
    this.pod = pod;
    this.properties = properties;
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

  /** {@code room-rest-api.md} §5. */
  ClaimedResult<JoinRoomResponse> join(
      UUID userId, UUID roomId, JoinRoomRequest request, @Nullable String inviteCode) {
    RoomRepository.JoinOutcome outcome =
        repository.joinRoom(
            userId, roomId, request.role(), inviteCode, properties.spectatorCapacity());
    String header = claimAndGetInstanceHeader(roomId);
    RoomResponse room = fetchResponse(roomId, userId);
    ReleasedRoomResponse released = outcome.releasedRoom().orElse(null);
    return new ClaimedResult<>(new JoinRoomResponse(room, released), header);
  }

  /** {@code room-rest-api.md} §6. */
  void leave(UUID userId, UUID roomId) {
    repository.leaveRoom(userId, roomId);
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
   * {@code room-lifecycle.md} §1.2/§1.7: claim, and on redirect or a lost adoption race ({@code
   * Superseded} — {@code IMP-M2-03}'s own "never retry in place" applies to the room-row write, not
   * to re-running the whole claim script), retry with a bounded backoff before giving up with the
   * {@code room-unavailable-retry} {@code 503}. Success always means this pod is the owner — {@code
   * room-rest-api.md} Done-when clause 5 — so the header is always this pod's own id.
   */
  private String claimAndGetInstanceHeader(UUID roomId) {
    for (int attempt = 1; attempt <= CLAIM_ATTEMPTS; attempt++) {
      RoomOwnershipService.ClaimOutcome outcome = ownership.claim(roomId);
      if (outcome instanceof RoomOwnershipService.ClaimOutcome.AlreadyMine
          || outcome instanceof RoomOwnershipService.ClaimOutcome.Adopted) {
        return pod.id();
      }
      if (attempt < CLAIM_ATTEMPTS) {
        sleep(CLAIM_RETRY_DELAY);
      }
    }
    throw new RoomUnavailableRetryException(
        "The room's owning instance is not currently reachable. Retry shortly.", RETRY_AFTER_MS);
  }

  private static void sleep(Duration duration) {
    try {
      Thread.sleep(duration.toMillis());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while retrying the room ownership claim", e);
    }
  }

  /**
   * Every caller other than {@link #resolveInviteCode} always has a room, hence always a header.
   */
  record ClaimedResult<T>(T body, String podInstanceHeader) {}

  /** {@code podInstanceHeader} is {@code null} exactly when {@code body} has zero elements. */
  record InviteCodeResult(PageResponse<RoomResponse> body, @Nullable String podInstanceHeader) {}
}
