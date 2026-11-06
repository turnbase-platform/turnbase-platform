package io.turnbase.platform.room;

import io.turnbase.platform.common.BusinessRuleException;
import io.turnbase.platform.common.NotFoundException;
import io.turnbase.platform.room.RoomRecords.ChatParticipantRow;
import io.turnbase.platform.room.RoomRecords.CreatableGame;
import io.turnbase.platform.room.RoomRecords.CreatableGameCandidate;
import io.turnbase.platform.room.RoomRecords.CreatedRoom;
import io.turnbase.platform.room.RoomRecords.JoinOutcome;
import io.turnbase.platform.room.RoomRecords.JoinPlan;
import io.turnbase.platform.room.RoomRecords.MemberAndState;
import io.turnbase.platform.room.RoomRecords.MemberForCommand;
import io.turnbase.platform.room.RoomRecords.PlayerJoinedPayload;
import io.turnbase.platform.room.RoomRecords.PlayerLeftPayload;
import io.turnbase.platform.room.RoomRecords.PlayerRoleChangedPayload;
import io.turnbase.platform.room.RoomRecords.ReadinessChangedPayload;
import io.turnbase.platform.room.RoomRecords.RoomForJoin;
import io.turnbase.platform.room.RoomRecords.TeamTemplateInfo;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Room creation, joining and leaving, and the member commands. Split from the former {@code
 * RoomRepository} by {@code IMP-M20-14}; SQL and transactions are as they were.
 */
@Repository
class RoomRepository {

  private final JdbcClient jdbcClient;
  private final RoomInvitationRepository invitationRepository;
  private final RoomOutboxRepository outbox;
  private final RoomReadRepository reads;

  RoomRepository(
      JdbcClient jdbcClient,
      RoomInvitationRepository invitationRepository,
      RoomOutboxRepository outbox,
      RoomReadRepository reads) {
    this.jdbcClient = jdbcClient;
    this.invitationRepository = invitationRepository;
    this.outbox = outbox;
    this.reads = reads;
  }

  // ---------------------------------------------------------------------
  // Catalog reads (data-model-catalog.md §1-2)
  // ---------------------------------------------------------------------

  /**
   * {@code data-model-catalog.md} §1 "Where the write-side rules are enforced" — the three
   * room-creation preconditions (FR-GAME-013, FR-GAME-007, FR-PRV-002), collapsed into one {@code
   * 404} per {@code room-rest-api.md} §2 ({@code IMP-GATE-41}). Empty when the game does not exist,
   * has no {@code ACTIVE} version, or fails any precondition.
   */
  Optional<CreatableGame> findCreatableGame(UUID gameId, UUID creatorId) {
    return jdbcClient
        .sql(
            """
            select g.game_id, g.title, g.owner_user_id, g.deleted_at, g.archived_at, g.listed,
                   g.badge, gv.version, gv.manifest, gv.min_players, gv.max_players,
                   gv.supports_spectators, gv.supports_mid_game_join
            from game g
            join game_version gv on gv.version_id = g.active_version_id
            where g.game_id = :gameId
            """)
        .param("gameId", gameId)
        .query(RoomReadRepository::mapCreatableGameCandidate)
        .optional()
        .filter(candidate -> candidate.isCreatableBy(creatorId))
        .map(CreatableGameCandidate::game);
  }

  // ---------------------------------------------------------------------
  // Create (room-rest-api.md §2)
  // ---------------------------------------------------------------------

  @Transactional
  CreatedRoom createRoom(UUID creatorId, CreateRoomRequest request, CreatableGame game) {
    UUID roomId = UUID.randomUUID();
    jdbcClient
        .sql(
            """
            insert into room (room_id, game_id, game_version, host_user_id, visibility, state,
                               spectating_enabled, invite_code, version)
            values (:roomId, :gameId, :gameVersion, :hostUserId, :visibility, 'LOBBY',
                    :spectatingEnabled, :inviteCode, 0)
            """)
        .param("roomId", roomId)
        .param("gameId", game.gameId())
        .param("gameVersion", game.version())
        .param("hostUserId", creatorId)
        .param("visibility", request.visibility().name())
        .param("spectatingEnabled", request.spectatingRequested())
        .param("inviteCode", InviteCodeGenerator.generate())
        .update();

    List<TeamTemplateInfo> templates = reads.parseTeamTemplates(game.manifest());
    UUID firstTeamId = null;
    // One multi-row insert for every initial team (IMP-M18-03): a statement per team made the
    // create path's statement count grow with the manifest's team count.
    StringBuilder teamRows = new StringBuilder();
    Map<String, Object> teamParams = new HashMap<>();
    teamParams.put("roomId", roomId);
    int row = 0;
    for (int templateIndex = 0; templateIndex < templates.size(); templateIndex++) {
      TeamTemplateInfo template = templates.get(templateIndex);
      for (int teamIndex = 0; teamIndex < template.countMin(); teamIndex++) {
        UUID teamId = UUID.randomUUID();
        if (firstTeamId == null) {
          firstTeamId = teamId;
        }
        if (row > 0) {
          teamRows.append(", ");
        }
        teamRows.append(
            "(:teamId%d, :roomId, :templateIndex%d, :teamIndex%d, :configuredSize%d)"
                .formatted(row, row, row, row));
        teamParams.put("teamId" + row, teamId);
        teamParams.put("templateIndex" + row, templateIndex);
        teamParams.put("teamIndex" + row, teamIndex);
        teamParams.put("configuredSize" + row, template.sizeMax());
        row++;
      }
    }
    if (row > 0) {
      jdbcClient
          .sql(
              "insert into room_team (team_id, room_id, template_index, team_index,"
                  + " configured_size) values "
                  + teamRows)
          .params(teamParams)
          .update();
    }

    // FR-TEAM-005: all teams are empty at t=0, so the deterministic assignment is the first
    // declared template's first team — game-rooms.md FR-TEAM-012.
    jdbcClient
        .sql(
            """
            insert into room_participant (room_id, user_id, role, team_id, join_order)
            values (:roomId, :userId, 'PLAYER', :teamId, 1)
            """)
        .param("roomId", roomId)
        .param("userId", creatorId)
        .param("teamId", firstTeamId)
        .update();

    return new CreatedRoom(roomId, releaseCallersCurrentRoom(creatorId, roomId));
  }

  // ---------------------------------------------------------------------
  // Join (room-rest-api.md §5)
  // ---------------------------------------------------------------------

  @Transactional
  JoinOutcome joinRoom(
      UUID userId,
      UUID roomId,
      ParticipantRole role,
      @Nullable String inviteCode,
      int spectatorCapacity) {
    JoinPlan plan = planJoin(userId, roomId, role, inviteCode, spectatorCapacity, true);
    if (plan.alreadyParticipant()) {
      return new JoinOutcome(
          roomId, releaseCallersCurrentRoom(userId, roomId), Optional.empty(), Optional.empty());
    }
    UUID teamId = plan.teamId();
    // data-model.md §2 / room-rest-api.md §5 step 6 (IMP-GATE-50): room_participant keeps one row
    // per (room_id, user_id) for a caller's entire history in this room, so a caller re-joining
    // after a previous departure (LEFT, KICKED, or GRACE_EXPIRED — this method is only reached at
    // all once step 2 above has already ruled out a live row) already owns this key. Reusing it
    // rather than plain-inserting is what makes that ordinary case not a primary-key violation.
    // The next join_order is computed inside the insert and returned (IMP-M18-03): the room row is
    // locked by planJoin, so the max cannot move between the read and the write, exactly as when
    // it was a separate statement.
    long nextJoinOrder =
        jdbcClient
            .sql(
                """
            insert into room_participant (room_id, user_id, role, team_id, join_order, joined_at,
                                           connection_count, ready, left_at, leave_reason,
                                           lost_announced_at, grace_reservation_id, grace_expires_at)
            values (:roomId, :userId, :role, :teamId,
                    (select coalesce(max(join_order), 0) + 1 from room_participant
                      where room_id = :roomId),
                    now(),
                    0, false, null, null, null, null, null)
            on conflict (room_id, user_id) do update
               set role = excluded.role, team_id = excluded.team_id,
                   join_order = excluded.join_order, joined_at = excluded.joined_at,
                   connection_count = 0, ready = false,
                   left_at = null, leave_reason = null, lost_announced_at = null,
                   grace_reservation_id = null, grace_expires_at = null
            returning join_order
            """)
            .param("roomId", roomId)
            .param("userId", userId)
            .param("role", role.name())
            .param("teamId", teamId)
            .query(Long.class)
            .single();

    Optional<ReleasedRoomResponse> released = releaseCallersCurrentRoom(userId, roomId);
    PlayerJoinedPayload joined =
        new PlayerJoinedPayload(userId, plan.callerUsername(), role.name(), teamId, nextJoinOrder);
    // room-rest-api.md §5 step 7 (room-invitation-rest-api.md §4): a successful join resolves the
    // caller's pending invitation to this room, if any; a failed join (thrown above) never reaches
    // here, so a rejected join leaves the invitation pending for a retry.
    Optional<RoomInvitationRepository.ResolvedInvitation> acceptedInvitation =
        invitationRepository.resolveOnAcceptedJoin(roomId, userId);
    return new JoinOutcome(roomId, released, Optional.of(joined), acceptedInvitation);
  }

  /**
   * {@code room-rest-api.md} §5 steps 1-4 with no write and no row lock, run <em>before</em> the
   * ownership claim (IMP-M10-03, AUD-03) so a caller who is going to be refused leaves no Redis key
   * behind. Throws exactly what {@link #joinRoom} would; the locked re-check inside the transaction
   * stays authoritative.
   */
  void precheckJoin(
      UUID userId,
      UUID roomId,
      ParticipantRole role,
      @Nullable String inviteCode,
      int spectatorCapacity) {
    planJoin(userId, roomId, role, inviteCode, spectatorCapacity, false);
  }

  private JoinPlan planJoin(
      UUID userId,
      UUID roomId,
      ParticipantRole role,
      @Nullable String inviteCode,
      int spectatorCapacity,
      boolean lock) {
    RoomForJoin room =
        jdbcClient
            .sql(
                """
                select r.room_id, r.visibility, r.state, r.spectating_enabled, r.host_user_id,
                       r.invite_code, gv.supports_spectators, gv.supports_mid_game_join,
                       (select username from app_user where user_id = :userId) as caller_username
                from room r
                join game_version gv on gv.game_id = r.game_id and gv.version = r.game_version
                where r.room_id = :roomId
                """
                    + (lock ? "for update" : ""))
            .param("roomId", roomId)
            .param("userId", userId)
            .query(RoomReadRepository::mapRoomForJoin)
            .optional()
            .orElseThrow(() -> new NotFoundException("No such room."));

    boolean currentParticipant = isCurrentParticipant(roomId, userId);
    boolean visibleToCaller =
        reads.isVisible(room.visibility(), room.hostUserId(), userId)
            || currentParticipant
            || hasPendingInvitation(roomId, userId)
            || (inviteCode != null && inviteCode.equals(room.inviteCode()));
    if (!visibleToCaller) {
      throw new NotFoundException("No such room.");
    }

    if (currentParticipant) {
      return new JoinPlan(true, null, room.callerUsername());
    }

    if ("CLOSED".equals(room.state())) {
      throw new NotFoundException("No such room.");
    }
    if (role == ParticipantRole.PLAYER
        && "IN_PROGRESS".equals(room.state())
        && !room.supportsMidGameJoin()) {
      throw new BusinessRuleException("This game does not support joining once it has started.");
    }

    UUID teamId = null;
    if (role == ParticipantRole.PLAYER) {
      Optional<UUID> availableTeam = findAvailableTeam(roomId);
      if (availableTeam.isEmpty()) {
        throw new BusinessRuleException("This room is full.");
      }
      teamId = availableTeam.get();
    } else {
      if (!room.spectatingEnabled() || !room.supportsSpectators()) {
        throw new BusinessRuleException("This room does not support spectating.");
      }
      long spectatorCount = countParticipants(roomId, "SPECTATOR");
      if (spectatorCount >= spectatorCapacity) {
        throw new BusinessRuleException("Spectator capacity has been reached.");
      }
    }
    return new JoinPlan(false, teamId, room.callerUsername());
  }

  private Optional<UUID> findAvailableTeam(UUID roomId) {
    return jdbcClient
        .sql(
            """
            select t.team_id
            from room_team t
            left join (
              select team_id, count(*) as occupied
              from room_participant
              where room_id = :roomId and role = 'PLAYER' and left_at is null
              group by team_id
            ) rc on rc.team_id = t.team_id
            where t.room_id = :roomId and coalesce(rc.occupied, 0) < t.configured_size
            order by coalesce(rc.occupied, 0) asc, t.template_index asc, t.team_index asc
            limit 1
            """)
        .param("roomId", roomId)
        .query(UUID.class)
        .optional();
  }

  /** {@code room-rest-api.md} §3 {@code reachable()}: a pending {@code room_invitation}. */
  private boolean hasPendingInvitation(UUID roomId, UUID userId) {
    return Boolean.TRUE.equals(
        jdbcClient
            .sql(
                "select exists(select 1 from room_invitation"
                    + " where room_id = :roomId and invitee_user_id = :userId)")
            .param("roomId", roomId)
            .param("userId", userId)
            .query(Boolean.class)
            .single());
  }

  /** Room or spectator membership, current only ({@code left_at is null}) — role-agnostic. */
  boolean isCurrentParticipant(UUID roomId, UUID userId) {
    Boolean exists =
        jdbcClient
            .sql(
                "select exists(select 1 from room_participant where room_id = :roomId and"
                    + " user_id = :userId and left_at is null)")
            .param("roomId", roomId)
            .param("userId", userId)
            .query(Boolean.class)
            .single();
    return Boolean.TRUE.equals(exists);
  }

  /**
   * {@code room-rest-api.md} §4 {@code viewerRole}: the caller's current role in the room ({@code
   * left_at is null}); a grace-period reservation is an open {@code PLAYER} row, so it counts.
   */
  Optional<String> findViewerRole(UUID roomId, UUID userId) {
    return jdbcClient
        .sql(
            "select role from room_participant where room_id = :roomId and user_id = :userId"
                + " and left_at is null")
        .param("roomId", roomId)
        .param("userId", userId)
        .query(String.class)
        .optional();
  }

  private long countParticipants(UUID roomId, String role) {
    Long count =
        jdbcClient
            .sql(
                "select count(*) from room_participant where room_id = :roomId and role = :role"
                    + " and left_at is null")
            .param("roomId", roomId)
            .param("role", role)
            .query(Long.class)
            .single();
    return count == null ? 0 : count;
  }

  /**
   * FR-JOIN-007, run at the end of create and join. The caller occupies at most one room at a time,
   * so a released membership names a different room than {@code newRoomId} by construction. {@code
   * lost_announced_at} is left {@code null} unconditionally: this handler makes no attempt to
   * determine or claim ownership of the *old* room, so the departure is honestly recorded as owed
   * an announcement, for the deferred-tail sweep (`room-lifecycle.md` §5.1 duty 2) to discharge.
   *
   * <p>{@code EARLY_RELEASE} (`reconnection-flow.md` FR-EXPIRY-004) additionally notifies the game
   * backend exactly as a normal expiry would ({@code game-contracts.md} FR-IN-005's closed reason
   * set has no fourth value for this case — early release is reported as {@code
   * grace_period_expired}, same as any other resolution of the reservation) — performed by this,
   * the acting instance, regardless of whether it owns the old room, since the outbox insert needs
   * no room-ownership fence, only the old room's own {@code active_session_id} ({@link
   * #insertPlayerLeftOutbox} is itself the conditional-on-session-existing guard).
   */
  private boolean isInProgress(UUID roomId) {
    return jdbcClient
        .sql("select state from room where room_id = :roomId")
        .param("roomId", roomId)
        .query(String.class)
        .optional()
        .filter("IN_PROGRESS"::equals)
        .isPresent();
  }

  private Optional<ReleasedRoomResponse> releaseCallersCurrentRoom(UUID userId, UUID newRoomId) {
    record ActiveMembership(
        UUID roomId, @Nullable UUID graceReservationId, @Nullable UUID teamId, String role) {}

    Optional<ActiveMembership> active =
        jdbcClient
            .sql(
                "select room_id, grace_reservation_id, team_id, role from room_participant"
                    + " where user_id = :userId and left_at is null and room_id <> :newRoomId")
            .param("userId", userId)
            .param("newRoomId", newRoomId)
            .query(
                (rs, rowNum) ->
                    new ActiveMembership(
                        rs.getObject("room_id", UUID.class),
                        rs.getObject("grace_reservation_id", UUID.class),
                        rs.getObject("team_id", UUID.class),
                        rs.getString("role")))
            .optional();
    if (active.isEmpty()) {
      return Optional.empty();
    }
    ActiveMembership membership = active.get();
    boolean earlyRelease = membership.graceReservationId() != null;
    String reason = earlyRelease ? "EARLY_RELEASE" : "LEFT";
    jdbcClient
        .sql(
            """
            update room_participant
               set left_at = now(), leave_reason = :reason, lost_announced_at = null,
                   grace_reservation_id = null, grace_expires_at = null
             where room_id = :roomId and user_id = :userId and left_at is null
            """)
        .param("reason", reason)
        .param("roomId", membership.roomId())
        .param("userId", userId)
        .update();
    if (earlyRelease) {
      outbox.insertPlayerLeftOutbox(
          membership.roomId(), userId, membership.teamId(), "grace_period_expired", true);
    } else if ("PLAYER".equals(membership.role()) && isInProgress(membership.roomId())) {
      // FR-LIFE-004: an abandonment confirmation is a confirmed leave — reason `left`.
      outbox.insertPlayerLeftOutbox(membership.roomId(), userId, membership.teamId(), "left", true);
    }
    return Optional.of(new ReleasedRoomResponse(membership.roomId(), reason));
  }

  // ---------------------------------------------------------------------
  // Leave (room-rest-api.md §6)
  // ---------------------------------------------------------------------

  /**
   * {@code FR-LIFE-004}: an immediate, confirmed departure — no grace period. Idempotent: a caller
   * with no active membership in this room updates zero rows and returns empty, distinct from a
   * real departure so {@code RoomService} knows whether a {@code PLAYER_LEFT} broadcast is owed.
   *
   * <p>{@code announceInline} is {@code room-lifecycle.md} §3.2's {@code :announceInline} — {@code
   * true} exactly when the caller owns the room and is about to broadcast immediately
   * (`RoomService#leave`'s claimed branch, and every socket-disconnect-triggered immediate leave);
   * {@code false} when it does not, leaving the departure for §5.1 duty 2 to announce.
   */
  @Transactional
  Optional<PlayerLeftPayload> leaveRoom(UUID userId, UUID roomId, boolean announceInline) {
    record Member(String role, @Nullable UUID teamId) {}
    Optional<Member> member =
        jdbcClient
            .sql(
                "select role, team_id from room_participant"
                    + " where room_id = :roomId and user_id = :userId and left_at is null"
                    + " for update")
            .param("roomId", roomId)
            .param("userId", userId)
            .query(
                (rs, rowNum) ->
                    new Member(rs.getString("role"), rs.getObject("team_id", UUID.class)))
            .optional();
    if (member.isEmpty()) {
      return Optional.empty();
    }
    jdbcClient
        .sql(
            """
            update room_participant
               set left_at = now(), leave_reason = 'LEFT',
                   lost_announced_at = case when :announceInline then now() else null end,
                   grace_reservation_id = null, grace_expires_at = null
             where room_id = :roomId and user_id = :userId and left_at is null
            """)
        .param("announceInline", announceInline)
        .param("roomId", roomId)
        .param("userId", userId)
        .update();
    // FR-LIFE-004: a confirmed leave during In Progress tells the engine at once (reason `left`),
    // exactly as grace-period expiry does. Only a PLAYER was ever represented to the engine.
    if ("PLAYER".equals(member.get().role()) && isInProgress(roomId)) {
      outbox.insertPlayerLeftOutbox(roomId, userId, member.get().teamId(), "left", true);
    }
    return Optional.of(new PlayerLeftPayload(userId, member.get().role()));
  }

  // ---------------------------------------------------------------------
  // Member commands (room-websocket.md, IMP-M2-05) — every method here runs inside
  // RoomEventExecutor's lock and transaction, so the row(s) it reads are the authoritative,
  // under-lock state room-websocket.md's Authorization Model requires.
  // ---------------------------------------------------------------------

  /** {@code TOGGLE_READY}, FR-READY-001/003. */
  ReadinessChangedPayload toggleReady(UUID roomId, UUID userId, boolean ready) {
    MemberForCommand member = requireEligiblePlayer(roomId, userId);
    requireLobby(member.state());
    jdbcClient
        .sql(
            "update room_participant set ready = :ready"
                + " where room_id = :roomId and user_id = :userId and left_at is null")
        .param("ready", ready)
        .param("roomId", roomId)
        .param("userId", userId)
        .update();
    return new ReadinessChangedPayload(userId, ready);
  }

  /** {@code SWITCH_TO_SPECTATOR}, FR-LIFE-005. */
  PlayerRoleChangedPayload switchToSpectator(UUID roomId, UUID userId, int spectatorCapacity) {
    MemberForCommand member = requireEligiblePlayer(roomId, userId);
    requireLobby(member.state());
    if (!member.spectatingEnabled() || !member.supportsSpectators()) {
      throw new RoomCommandException(
          "SPECTATING_NOT_ENABLED", "This room does not support spectating.");
    }
    if (countParticipants(roomId, "SPECTATOR") >= spectatorCapacity) {
      throw new RoomCommandException(
          "SPECTATOR_CAPACITY_FULL", "Spectator capacity has been reached.");
    }
    jdbcClient
        .sql(
            "update room_participant set role = 'SPECTATOR', team_id = null, ready = false"
                + " where room_id = :roomId and user_id = :userId and left_at is null")
        .param("roomId", roomId)
        .param("userId", userId)
        .update();
    return new PlayerRoleChangedPayload(userId, "SPECTATOR", null, true);
  }

  /** {@code SWITCH_TO_PLAYER}, FR-LIFE-006. */
  PlayerRoleChangedPayload switchToPlayer(UUID roomId, UUID userId) {
    MemberAndState member = requireMember(roomId, userId);
    requireLobby(member.state());
    if (!"SPECTATOR".equals(member.role())) {
      throw new RoomCommandException("ROLE_NOT_ELIGIBLE", "Only a spectator may switch to player.");
    }
    UUID teamId =
        findAvailableTeam(roomId)
            .orElseThrow(
                () ->
                    new RoomCommandException(
                        "TEAM_FULL", "No player slot is available in this room."));
    jdbcClient
        .sql(
            "update room_participant set role = 'PLAYER', team_id = :teamId, ready = false"
                + " where room_id = :roomId and user_id = :userId and left_at is null")
        .param("teamId", teamId)
        .param("roomId", roomId)
        .param("userId", userId)
        .update();
    return new PlayerRoleChangedPayload(userId, "PLAYER", teamId, false);
  }

  /** {@code TOGGLE_READY}/{@code SWITCH_TO_SPECTATOR}: non-host human player, any room state. */
  private MemberForCommand requireEligiblePlayer(UUID roomId, UUID userId) {
    Optional<MemberForCommand> row =
        jdbcClient
            .sql(
                """
                select rp.role, r.host_user_id, r.state, r.spectating_enabled,
                       gv.supports_spectators
                from room_participant rp
                join room r on r.room_id = rp.room_id
                join game_version gv on gv.game_id = r.game_id and gv.version = r.game_version
                where rp.room_id = :roomId and rp.user_id = :userId and rp.left_at is null
                """)
            .param("roomId", roomId)
            .param("userId", userId)
            .query(
                (rs, rowNum) ->
                    new MemberForCommand(
                        rs.getString("role"),
                        rs.getObject("host_user_id", UUID.class),
                        rs.getString("state"),
                        rs.getBoolean("spectating_enabled"),
                        rs.getBoolean("supports_spectators")))
            .optional();
    MemberForCommand member =
        row.orElseThrow(
            () -> new RoomCommandException("NOT_MEMBER", "You are not a member of this room."));
    if (!"PLAYER".equals(member.role()) || userId.equals(member.hostUserId())) {
      throw new RoomCommandException(
          "ROLE_NOT_ELIGIBLE", "Only a non-host player may send this command.");
    }
    return member;
  }

  private MemberAndState requireMember(UUID roomId, UUID userId) {
    Optional<MemberAndState> row =
        jdbcClient
            .sql(
                "select rp.role, r.state from room_participant rp join room r"
                    + " on r.room_id = rp.room_id"
                    + " where rp.room_id = :roomId and rp.user_id = :userId and rp.left_at is null")
            .param("roomId", roomId)
            .param("userId", userId)
            .query((rs, rowNum) -> new MemberAndState(rs.getString("role"), rs.getString("state")))
            .optional();
    return row.orElseThrow(
        () -> new RoomCommandException("NOT_MEMBER", "You are not a member of this room."));
  }

  // ---------------------------------------------------------------------
  // Chat (room-chat-websocket.md, IMP-M2-11) — the read RoomChatSupport exposes on room's public
  // API to the chat feature. Unlocked: the bare-topic subscribe authorizer's own
  // isCurrentParticipant precedent above is a plain read too; only the send path needs
  // RoomEventExecutor's lock, for its serverTimestamp/team-resolution race with REASSIGN_PLAYER.
  // ---------------------------------------------------------------------

  /**
   * {@code room-chat-websocket.md} § Authorization / § `CHAT_MESSAGE`: the sender's current role
   * and team, plus whether the room has any team at all ({@code teamChatAvailable}, FR-TEAM-001 — a
   * team channel exists only once the game's manifest declared at least one team template, which is
   * exactly when {@code createRoom} inserted a {@code room_team} row).
   */
  Optional<ChatParticipantRow> currentParticipantForChat(UUID roomId, UUID userId) {
    return jdbcClient
        .sql(
            """
            select rp.role, rp.team_id, au.username,
                   exists(select 1 from room_team rt where rt.room_id = rp.room_id)
                     as team_chat_available
            from room_participant rp
            join app_user au on au.user_id = rp.user_id
            where rp.room_id = :roomId and rp.user_id = :userId and rp.left_at is null
            """)
        .param("roomId", roomId)
        .param("userId", userId)
        .query(
            (rs, rowNum) ->
                new ChatParticipantRow(
                    rs.getString("role"),
                    rs.getObject("team_id", UUID.class),
                    rs.getString("username"),
                    rs.getBoolean("team_chat_available")))
        .optional();
  }

  void requireLobby(String state) {
    if (!"LOBBY".equals(state)) {
      throw new RoomCommandException("ROOM_NOT_LOBBY", "This command requires the Lobby state.");
    }
  }
}
