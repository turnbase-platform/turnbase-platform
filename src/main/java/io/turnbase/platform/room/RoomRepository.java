package io.turnbase.platform.room;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.turnbase.platform.common.BusinessRuleException;
import io.turnbase.platform.common.NotFoundException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code data-model.md} §§1-3 ({@code room}, {@code room_participant}, {@code room_team}) and
 * {@code data-model-catalog.md} §§1-2 ({@code game}, {@code game_version}) — direct SQL against
 * catalog tables follows {@code social.SocialRepository}'s precedent against {@code app_user}:
 * Spring Modulith's boundary is a Java dependency graph, not a schema ownership map, and no {@code
 * catalog} feature exists yet to own a Java API for these columns ({@code IMP-GATE-41}).
 */
@Repository
class RoomRepository {

  private final JdbcClient jdbcClient;
  private final ObjectMapper objectMapper;
  private final RoomInvitationRepository invitationRepository;

  RoomRepository(
      JdbcClient jdbcClient,
      ObjectMapper objectMapper,
      RoomInvitationRepository invitationRepository) {
    this.jdbcClient = jdbcClient;
    this.objectMapper = objectMapper;
    this.invitationRepository = invitationRepository;
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
        .query(RoomRepository::mapCreatableGameCandidate)
        .optional()
        .filter(candidate -> candidate.isCreatableBy(creatorId))
        .map(CreatableGameCandidate::game);
  }

  // ---------------------------------------------------------------------
  // Create (room-rest-api.md §2)
  // ---------------------------------------------------------------------

  @Transactional
  UUID createRoom(UUID creatorId, CreateRoomRequest request, CreatableGame game) {
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

    List<TeamTemplateInfo> templates = parseTeamTemplates(game.manifest());
    UUID firstTeamId = null;
    for (int templateIndex = 0; templateIndex < templates.size(); templateIndex++) {
      TeamTemplateInfo template = templates.get(templateIndex);
      for (int teamIndex = 0; teamIndex < template.countMin(); teamIndex++) {
        UUID teamId = UUID.randomUUID();
        if (firstTeamId == null) {
          firstTeamId = teamId;
        }
        jdbcClient
            .sql(
                """
                insert into room_team (team_id, room_id, template_index, team_index, configured_size)
                values (:teamId, :roomId, :templateIndex, :teamIndex, :configuredSize)
                """)
            .param("teamId", teamId)
            .param("roomId", roomId)
            .param("templateIndex", templateIndex)
            .param("teamIndex", teamIndex)
            .param("configuredSize", template.sizeMax())
            .update();
      }
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

    releaseCallersCurrentRoom(creatorId, roomId);
    return roomId;
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
    RoomForJoin room =
        jdbcClient
            .sql(
                """
                select r.room_id, r.visibility, r.state, r.spectating_enabled, r.host_user_id,
                       r.invite_code, gv.supports_spectators, gv.supports_mid_game_join
                from room r
                join game_version gv on gv.game_id = r.game_id and gv.version = r.game_version
                where r.room_id = :roomId
                for update
                """)
            .param("roomId", roomId)
            .query(RoomRepository::mapRoomForJoin)
            .optional()
            .orElseThrow(() -> new NotFoundException("No such room."));

    boolean visibleToCaller =
        isVisible(room.visibility(), room.hostUserId(), userId)
            || (inviteCode != null && inviteCode.equals(room.inviteCode()));
    if (!visibleToCaller) {
      throw new NotFoundException("No such room.");
    }

    if (isCurrentParticipant(roomId, userId)) {
      return new JoinOutcome(
          roomId, releaseCallersCurrentRoom(userId, roomId), Optional.empty(), Optional.empty());
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
        throw new BusinessRuleException("No player slot is available in this room.");
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

    long nextJoinOrder = nextJoinOrder(roomId);
    // data-model.md §2 / room-rest-api.md §5 step 6 (IMP-GATE-50): room_participant keeps one row
    // per (room_id, user_id) for a caller's entire history in this room, so a caller re-joining
    // after a previous departure (LEFT, KICKED, or GRACE_EXPIRED — this method is only reached at
    // all once step 2 above has already ruled out a live row) already owns this key. Reusing it
    // rather than plain-inserting is what makes that ordinary case not a primary-key violation.
    jdbcClient
        .sql(
            """
            insert into room_participant (room_id, user_id, role, team_id, join_order, joined_at,
                                           connection_count, ready, left_at, leave_reason,
                                           lost_announced_at, grace_reservation_id, grace_expires_at)
            values (:roomId, :userId, :role, :teamId, :joinOrder, now(),
                    0, false, null, null, null, null, null)
            on conflict (room_id, user_id) do update
               set role = excluded.role, team_id = excluded.team_id,
                   join_order = excluded.join_order, joined_at = excluded.joined_at,
                   connection_count = 0, ready = false,
                   left_at = null, leave_reason = null, lost_announced_at = null,
                   grace_reservation_id = null, grace_expires_at = null
            """)
        .param("roomId", roomId)
        .param("userId", userId)
        .param("role", role.name())
        .param("teamId", teamId)
        .param("joinOrder", nextJoinOrder)
        .update();

    Optional<ReleasedRoomResponse> released = releaseCallersCurrentRoom(userId, roomId);
    PlayerJoinedPayload joined =
        new PlayerJoinedPayload(userId, usernameOf(userId), role.name(), teamId, nextJoinOrder);
    // room-rest-api.md §5 step 7 (room-invitation-rest-api.md §4): a successful join resolves the
    // caller's pending invitation to this room, if any; a failed join (thrown above) never reaches
    // here, so a rejected join leaves the invitation pending for a retry.
    Optional<RoomInvitationRepository.ResolvedInvitation> acceptedInvitation =
        invitationRepository.resolveOnAcceptedJoin(roomId, userId);
    return new JoinOutcome(roomId, released, Optional.of(joined), acceptedInvitation);
  }

  private String usernameOf(UUID userId) {
    return jdbcClient
        .sql("select username from app_user where user_id = :userId")
        .param("userId", userId)
        .query(String.class)
        .single();
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
              where role = 'PLAYER' and left_at is null
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

  private long nextJoinOrder(UUID roomId) {
    Long max =
        jdbcClient
            .sql("select max(join_order) from room_participant where room_id = :roomId")
            .param("roomId", roomId)
            .query(Long.class)
            .single();
    return (max == null ? 0 : max) + 1;
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
  private Optional<ReleasedRoomResponse> releaseCallersCurrentRoom(UUID userId, UUID newRoomId) {
    record ActiveMembership(
        UUID roomId, @Nullable UUID graceReservationId, @Nullable UUID teamId) {}

    Optional<ActiveMembership> active =
        jdbcClient
            .sql(
                "select room_id, grace_reservation_id, team_id from room_participant"
                    + " where user_id = :userId and left_at is null and room_id <> :newRoomId")
            .param("userId", userId)
            .param("newRoomId", newRoomId)
            .query(
                (rs, rowNum) ->
                    new ActiveMembership(
                        rs.getObject("room_id", UUID.class),
                        rs.getObject("grace_reservation_id", UUID.class),
                        rs.getObject("team_id", UUID.class)))
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
      insertPlayerLeftOutbox(
          membership.roomId(), userId, membership.teamId(), "grace_period_expired");
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
    Optional<String> role =
        jdbcClient
            .sql(
                "select role from room_participant"
                    + " where room_id = :roomId and user_id = :userId and left_at is null"
                    + " for update")
            .param("roomId", roomId)
            .param("userId", userId)
            .query(String.class)
            .optional();
    if (role.isEmpty()) {
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
    return Optional.of(new PlayerLeftPayload(userId, role.get()));
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

  record ChatParticipantRow(
      String role, @Nullable UUID teamId, String username, boolean teamChatAvailable) {}

  private void requireLobby(String state) {
    if (!"LOBBY".equals(state)) {
      throw new RoomCommandException("ROOM_NOT_LOBBY", "This command requires the Lobby state.");
    }
  }

  // ---------------------------------------------------------------------
  // Host commands (room-websocket.md). IMP-M2-05's own share: ADD_TEAM, REMOVE_TEAM, RESIZE_TEAM,
  // REASSIGN_PLAYER, ADD_AI_PLAYER. IMP-M2-06's share (KICK_PLAYER, CHANGE_PARAMETERS, START_GAME,
  // TRANSFER_HOST) follows below, after ADD_AI_PLAYER/requireHost.
  // ---------------------------------------------------------------------

  /** {@code ADD_TEAM}, FR-TEAM-002. */
  TeamAddedPayload addTeam(UUID roomId, UUID hostUserId, String templateName, int configuredSize) {
    requireLobby(requireHost(roomId, hostUserId));
    List<TeamTemplateInfo> templates = teamTemplatesForRoom(roomId);
    int templateIndex = -1;
    TeamTemplateInfo template = null;
    for (int i = 0; i < templates.size(); i++) {
      if (templates.get(i).name().equals(templateName)) {
        templateIndex = i;
        template = templates.get(i);
        break;
      }
    }
    if (template == null) {
      throw new RoomCommandException(
          "VALIDATION_FAILED",
          "No such team template.",
          Map.of("field", "templateName", "value", templateName));
    }
    if (configuredSize < template.sizeMin() || configuredSize > template.sizeMax()) {
      throw new RoomCommandException(
          "SIZE_OUT_OF_RANGE",
          "configuredSize must be within [%d, %d]."
              .formatted(template.sizeMin(), template.sizeMax()));
    }
    long currentCount = countTeamsForTemplate(roomId, templateIndex);
    if (currentCount >= template.countMax()) {
      throw new RoomCommandException(
          "TEMPLATE_MAX_TEAMS", "This template already has the maximum number of teams.");
    }
    UUID teamId = UUID.randomUUID();
    int teamIndex = (int) currentCount;
    jdbcClient
        .sql(
            """
            insert into room_team (team_id, room_id, template_index, team_index, configured_size)
            values (:teamId, :roomId, :templateIndex, :teamIndex, :configuredSize)
            """)
        .param("teamId", teamId)
        .param("roomId", roomId)
        .param("templateIndex", templateIndex)
        .param("teamIndex", teamIndex)
        .param("configuredSize", configuredSize)
        .update();
    return new TeamAddedPayload(teamId, templateName, configuredSize, teamIndex);
  }

  /** {@code REMOVE_TEAM}, FR-TEAM-003. */
  TeamRemovedPayload removeTeam(UUID roomId, UUID hostUserId, UUID teamId) {
    requireLobby(requireHost(roomId, hostUserId));
    requireTeamInRoom(roomId, teamId);
    long occupied = teamOccupancy(teamId);
    if (occupied > 0) {
      throw new RoomCommandException("TEAM_NOT_EMPTY", "This team still has members.");
    }
    jdbcClient
        .sql("delete from room_team where team_id = :teamId and room_id = :roomId")
        .param("teamId", teamId)
        .param("roomId", roomId)
        .update();
    return new TeamRemovedPayload(teamId);
  }

  /** {@code RESIZE_TEAM}, FR-TEAM-004. */
  TeamResizedPayload resizeTeam(UUID roomId, UUID hostUserId, UUID teamId, int newSize) {
    requireLobby(requireHost(roomId, hostUserId));
    int templateIndex = requireTeamInRoom(roomId, teamId);
    TeamTemplateInfo template = teamTemplatesForRoom(roomId).get(templateIndex);
    if (newSize < template.sizeMin() || newSize > template.sizeMax()) {
      throw new RoomCommandException(
          "SIZE_OUT_OF_RANGE",
          "newSize must be within [%d, %d].".formatted(template.sizeMin(), template.sizeMax()));
    }
    long occupied = teamOccupancy(teamId);
    if (newSize < occupied) {
      throw new RoomCommandException(
          "OVER_CAPACITY", "newSize would reduce capacity below the current member count.");
    }
    jdbcClient
        .sql("update room_team set configured_size = :newSize where team_id = :teamId")
        .param("newSize", newSize)
        .param("teamId", teamId)
        .update();
    return new TeamResizedPayload(teamId, newSize);
  }

  /** {@code REASSIGN_PLAYER}, FR-TEAM-006/007. */
  PlayerReassignedPayload reassignPlayer(
      UUID roomId, UUID hostUserId, UUID playerId, UUID toTeamId) {
    requireLobby(requireHost(roomId, hostUserId));
    UUID fromTeamId =
        jdbcClient
            .sql(
                "select team_id from room_participant where room_id = :roomId"
                    + " and user_id = :playerId and role = 'PLAYER' and left_at is null")
            .param("roomId", roomId)
            .param("playerId", playerId)
            .query(UUID.class)
            .optional()
            .orElseThrow(
                () -> new RoomCommandException("PLAYER_NOT_FOUND", "No such player in this room."));
    int configuredSize =
        jdbcClient
            .sql(
                "select configured_size from room_team where team_id = :teamId and room_id ="
                    + " :roomId")
            .param("teamId", toTeamId)
            .param("roomId", roomId)
            .query(Integer.class)
            .optional()
            .orElseThrow(
                () ->
                    new RoomCommandException(
                        "VALIDATION_FAILED",
                        "No such team.",
                        Map.of("field", "toTeamId", "value", toTeamId.toString())));
    if (teamOccupancy(toTeamId) >= configuredSize) {
      throw new RoomCommandException("TEAM_FULL", "The target team has no available slot.");
    }
    jdbcClient
        .sql(
            "update room_participant set team_id = :teamId, ready = false"
                + " where room_id = :roomId and user_id = :playerId and left_at is null")
        .param("teamId", toTeamId)
        .param("roomId", roomId)
        .param("playerId", playerId)
        .update();
    return new PlayerReassignedPayload(playerId, fromTeamId, toTeamId, true);
  }

  /**
   * {@code ADD_AI_PLAYER} — wire-present, behaviour-absent (IR-12). Always fails once the sender is
   * confirmed to be the host; no v1 game may declare {@code capabilities.ai}. Returns rather than
   * throws so the caller — inside a {@code RoomMutation} lambda that must otherwise return a {@code
   * MutationOutcome} — can express "always fails" as a single {@code throw} statement.
   */
  RoomCommandException addAiPlayer(UUID roomId, UUID hostUserId) {
    requireHost(roomId, hostUserId);
    return new RoomCommandException(
        "AI_NOT_SUPPORTED", "AI players are not supported in this version of the platform.");
  }

  /** Returns the room's current state; throws {@code NOT_HOST} if {@code userId} is not it. */
  private String requireHost(UUID roomId, UUID userId) {
    RoomHostAndState row =
        jdbcClient
            .sql("select host_user_id, state from room where room_id = :roomId")
            .param("roomId", roomId)
            .query(
                (rs, rowNum) ->
                    new RoomHostAndState(
                        rs.getObject("host_user_id", UUID.class), rs.getString("state")))
            .optional()
            .orElseThrow(() -> new IllegalStateException("room " + roomId + " vanished"));
    if (!userId.equals(row.hostUserId())) {
      throw new RoomCommandException("NOT_HOST", "Only the room's host may send this command.");
    }
    return row.state();
  }

  /** Validates {@code teamId} belongs to {@code roomId}; returns its {@code template_index}. */
  private int requireTeamInRoom(UUID roomId, UUID teamId) {
    return jdbcClient
        .sql("select template_index from room_team where team_id = :teamId and room_id = :roomId")
        .param("teamId", teamId)
        .param("roomId", roomId)
        .query(Integer.class)
        .optional()
        .orElseThrow(
            () ->
                new RoomCommandException(
                    "VALIDATION_FAILED",
                    "No such team.",
                    Map.of("field", "teamId", "value", teamId.toString())));
  }

  private long countTeamsForTemplate(UUID roomId, int templateIndex) {
    Long count =
        jdbcClient
            .sql(
                "select count(*) from room_team where room_id = :roomId"
                    + " and template_index = :templateIndex")
            .param("roomId", roomId)
            .param("templateIndex", templateIndex)
            .query(Long.class)
            .single();
    return count == null ? 0 : count;
  }

  private long teamOccupancy(UUID teamId) {
    Long count =
        jdbcClient
            .sql(
                "select count(*) from room_participant where team_id = :teamId"
                    + " and role = 'PLAYER' and left_at is null")
            .param("teamId", teamId)
            .query(Long.class)
            .single();
    return count == null ? 0 : count;
  }

  // ---------------------------------------------------------------------
  // Host commands (room-websocket.md, IMP-M2-06's own share: KICK_PLAYER, CHANGE_PARAMETERS,
  // START_GAME, TRANSFER_HOST).
  // ---------------------------------------------------------------------

  /**
   * {@code KICK_PLAYER}, FR-HOST-006. Valid in any room state, against a player, spectator, or AI
   * member — but no AI seat can ever exist (IR-12), so only the first two are reachable. The
   * removal itself is the same {@code left_at}/{@code leave_reason} shape every other departure
   * uses; {@code lost_announced_at} is stamped {@code now()} because this method only ever runs
   * under {@link RoomEventExecutor}'s lock, i.e. on the owning instance, which announces inline.
   */
  PlayerKickedPayload kickPlayer(UUID roomId, UUID hostUserId, UUID targetPlayerId) {
    String roomState = requireHost(roomId, hostUserId);
    TargetMember target =
        jdbcClient
            .sql(
                "select role, team_id from room_participant"
                    + " where room_id = :roomId and user_id = :targetPlayerId and left_at is null")
            .param("roomId", roomId)
            .param("targetPlayerId", targetPlayerId)
            .query(
                (rs, rowNum) ->
                    new TargetMember(rs.getString("role"), rs.getObject("team_id", UUID.class)))
            .optional()
            .orElseThrow(
                () -> new RoomCommandException("PLAYER_NOT_FOUND", "No such player in this room."));

    jdbcClient
        .sql(
            """
            update room_participant
               set left_at = now(), leave_reason = 'KICKED', lost_announced_at = now(),
                   grace_reservation_id = null, grace_expires_at = null
             where room_id = :roomId and user_id = :targetPlayerId and left_at is null
            """)
        .param("roomId", roomId)
        .param("targetPlayerId", targetPlayerId)
        .update();

    // game-contracts.md FR-IN-005 — only a human PLAYER's removal is a game-backend input; a
    // spectator was never represented to the game backend in the first place, and no session
    // exists for the outbox row to reference until IMP-M3-05 (data-model.md §4.2, session_id NOT
    // NULL) — the same conditional shape as room-lifecycle.md §3.3's grace-expiry insert.
    if ("IN_PROGRESS".equals(roomState) && "PLAYER".equals(target.role())) {
      insertPlayerLeftOutbox(roomId, targetPlayerId, target.teamId(), "kicked");
    }
    return new PlayerKickedPayload(targetPlayerId, hostUserId);
  }

  private void insertPlayerLeftOutbox(
      UUID roomId, UUID playerId, @Nullable UUID teamId, String reason) {
    UUID sessionId =
        jdbcClient
            .sql("select active_session_id from room where room_id = :roomId")
            .param("roomId", roomId)
            .query(UUID.class)
            .optional()
            .orElse(null);
    if (sessionId == null) {
      return;
    }
    String username = usernameOf(playerId);
    Map<String, Object> payload =
        Map.of(
            "type",
            "player_left",
            "playerId",
            playerId.toString(),
            "displayName",
            username,
            "teamId",
            teamId == null ? "" : teamId.toString(),
            "reason",
            reason);
    String payloadJson;
    try {
      payloadJson = objectMapper.writeValueAsString(payload);
    } catch (Exception e) {
      throw new IllegalStateException("Failed to serialize player_left outbox payload", e);
    }
    jdbcClient
        .sql(
            "insert into game_input_outbox (session_id, payload) values (:sessionId, :payload::jsonb)")
        .param("sessionId", sessionId)
        .param("payload", payloadJson)
        .update();
  }

  /**
   * {@code CHANGE_PARAMETERS}, FR-HOST-007. {@code input-validation.md}'s whitelist: every key
   * validated against the manifest's declared schema, then merged into the existing {@code
   * room.parameters} — a key omitted from the command retains its stored value — and the full
   * merged set persisted and returned for broadcast (FR-HOST-008 relies on this being the durable
   * copy, not something reconstructed from the manifest's defaults).
   */
  ParametersChangedPayload changeParameters(
      UUID roomId, UUID hostUserId, Map<String, JsonNode> requested) {
    requireLobby(requireHost(roomId, hostUserId));
    List<ParameterSchema> schemas = parameterSchemasForRoom(roomId);
    Map<String, ParameterSchema> byKey = new java.util.LinkedHashMap<>();
    for (ParameterSchema schema : schemas) {
      byKey.put(schema.key(), schema);
    }

    RoomParametersRow row =
        jdbcClient
            .sql("select parameters from room where room_id = :roomId")
            .param("roomId", roomId)
            .query((rs, rowNum) -> new RoomParametersRow(rs.getString("parameters")))
            .single();
    Map<String, Object> merged =
        new java.util.LinkedHashMap<>(readParameters(row.parametersJson()));

    for (var entry : requested.entrySet()) {
      String key = entry.getKey();
      ParameterSchema schema = byKey.get(key);
      if (schema == null) {
        throw new RoomCommandException(
            "UNKNOWN_PARAMETER", "No such parameter.", Map.of("field", key));
      }
      merged.put(key, validatedParameterValue(schema, entry.getValue()));
    }

    String mergedJson;
    try {
      mergedJson = objectMapper.writeValueAsString(merged);
    } catch (Exception e) {
      throw new IllegalStateException("Failed to serialize room.parameters", e);
    }
    jdbcClient
        .sql("update room set parameters = :parameters::jsonb where room_id = :roomId")
        .param("parameters", mergedJson)
        .param("roomId", roomId)
        .update();
    return new ParametersChangedPayload(merged);
  }

  /** {@code input-validation.md}'s whitelist table, one parameter at a time. */
  private Object validatedParameterValue(ParameterSchema schema, JsonNode value) {
    String key = schema.key();
    switch (schema.type()) {
      case "integer" -> {
        if (!value.isIntegralNumber()) {
          throw parameterTypeMismatch(key);
        }
        long v = value.asLong();
        if (v < schema.min() || v > schema.max()) {
          throw parameterOutOfRange(key);
        }
        return v;
      }
      case "decimal" -> {
        if (!value.isNumber()) {
          throw parameterTypeMismatch(key);
        }
        double v = value.asDouble();
        if (v < schema.min() || v > schema.max()) {
          throw parameterOutOfRange(key);
        }
        return v;
      }
      case "boolean" -> {
        if (!value.isBoolean()) {
          throw parameterTypeMismatch(key);
        }
        return value.asBoolean();
      }
      case "string" -> {
        if (!value.isTextual()) {
          throw parameterTypeMismatch(key);
        }
        String v = value.asText();
        if (schema.maxLength() != null && v.length() > schema.maxLength()) {
          throw parameterOutOfRange(key);
        }
        return v;
      }
      case "enum" -> {
        if (!value.isTextual()) {
          throw parameterTypeMismatch(key);
        }
        String v = value.asText();
        if (schema.options() == null || !schema.options().contains(v)) {
          throw parameterOutOfRange(key);
        }
        return v;
      }
      default -> throw new IllegalStateException("Unrecognised parameter type: " + schema.type());
    }
  }

  private static RoomCommandException parameterTypeMismatch(String key) {
    return new RoomCommandException(
        "PARAMETER_TYPE_MISMATCH", "Value does not match the declared type.", Map.of("field", key));
  }

  private static RoomCommandException parameterOutOfRange(String key) {
    return new RoomCommandException(
        "PARAMETER_OUT_OF_RANGE", "Value is outside the declared bounds.", Map.of("field", key));
  }

  /**
   * {@code START_GAME}, FR-HOST-009. FR-TEAM-009's three conditions, evaluated atomically under the
   * room lock; every unmet condition is collected before throwing, not just the first
   * (`START_CONDITIONS_NOT_MET`'s `details` "lists the unmet conditions").
   */
  void checkStartConditions(UUID roomId, UUID hostUserId) {
    requireLobby(requireHost(roomId, hostUserId));
    List<String> failed = new ArrayList<>();

    List<TeamTemplateInfo> templates = teamTemplatesForRoom(roomId);
    List<TeamOccupancyRow> teamRows =
        jdbcClient
            .sql(
                """
                select t.team_id, t.template_index, coalesce(rc.occupied, 0) as occupied
                from room_team t
                left join (select team_id, count(*) as occupied from room_participant
                           where role = 'PLAYER' and left_at is null group by team_id) rc
                  on rc.team_id = t.team_id
                where t.room_id = :roomId
                """)
            .param("roomId", roomId)
            .query(
                (rs, rowNum) ->
                    new TeamOccupancyRow(
                        rs.getObject("team_id", UUID.class),
                        rs.getInt("template_index"),
                        rs.getLong("occupied")))
            .list();

    Map<Integer, Long> teamCountByTemplate = new java.util.LinkedHashMap<>();
    for (TeamOccupancyRow row : teamRows) {
      teamCountByTemplate.merge(row.templateIndex(), 1L, Long::sum);
      TeamTemplateInfo template = templates.get(row.templateIndex());
      if (row.occupied() < template.sizeMin() || row.occupied() > template.sizeMax()) {
        failed.add(
            "Team %s has %d player(s), needs between %d and %d."
                .formatted(row.teamId(), row.occupied(), template.sizeMin(), template.sizeMax()));
      }
    }
    for (int i = 0; i < templates.size(); i++) {
      TeamTemplateInfo template = templates.get(i);
      long count = teamCountByTemplate.getOrDefault(i, 0L);
      if (count < template.countMin() || count > template.countMax()) {
        failed.add(
            "Template \"%s\" has %d team(s), needs between %d and %d."
                .formatted(template.name(), count, template.countMin(), template.countMax()));
      }
    }

    List<String> notReady =
        jdbcClient
            .sql(
                """
                select au.username from room_participant rp join app_user au on au.user_id = rp.user_id
                where rp.room_id = :roomId and rp.role = 'PLAYER' and rp.left_at is null
                  and rp.user_id <> :hostUserId and rp.ready = false
                """)
            .param("roomId", roomId)
            .param("hostUserId", hostUserId)
            .query(String.class)
            .list();
    for (String username : notReady) {
      failed.add("Player \"%s\" is not ready.".formatted(username));
    }

    if (!failed.isEmpty()) {
      throw new RoomCommandException(
          "START_CONDITIONS_NOT_MET",
          "The team-validity conditions for starting the game are not met.",
          Map.of("failedConditions", failed));
    }
  }

  /**
   * {@code TRANSFER_HOST}, FR-XFER-004. The former host keeps their slot and player role — only
   * {@code room.host_user_id} moves.
   */
  HostTransferredPayload transferHost(UUID roomId, UUID hostUserId, UUID newHostId) {
    requireHost(roomId, hostUserId);
    Optional<String> targetRole =
        jdbcClient
            .sql(
                "select role from room_participant"
                    + " where room_id = :roomId and user_id = :newHostId and left_at is null")
            .param("roomId", roomId)
            .param("newHostId", newHostId)
            .query(String.class)
            .optional();
    if (targetRole.isEmpty() || !"PLAYER".equals(targetRole.get())) {
      throw new RoomCommandException(
          "TARGET_NOT_PLAYER", "The new host must be a current human player.");
    }
    jdbcClient
        .sql("update room set host_user_id = :newHostId where room_id = :roomId")
        .param("newHostId", newHostId)
        .param("roomId", roomId)
        .update();
    return new HostTransferredPayload(newHostId, hostUserId, "VOLUNTARY");
  }

  private List<ParameterSchema> parameterSchemasForRoom(UUID roomId) {
    String manifest =
        jdbcClient
            .sql(
                """
                select gv.manifest
                from room r join game_version gv
                  on gv.game_id = r.game_id and gv.version = r.game_version
                where r.room_id = :roomId
                """)
            .param("roomId", roomId)
            .query(String.class)
            .single();
    return parseParameterSchemas(manifest);
  }

  private List<ParameterSchema> parseParameterSchemas(@Nullable String manifestJson) {
    if (manifestJson == null) {
      throw new IllegalStateException("An ACTIVE game_version must carry a validated manifest.");
    }
    JsonNode manifest;
    try {
      manifest = objectMapper.readTree(manifestJson);
    } catch (Exception e) {
      throw new IllegalStateException("Stored manifest is not valid JSON", e);
    }
    List<ParameterSchema> schemas = new ArrayList<>();
    for (JsonNode node : manifest.path("parameters")) {
      String type = node.path("type").asText();
      schemas.add(
          new ParameterSchema(
              node.path("key").asText(),
              type,
              node.path("min").asDouble(0),
              node.path("max").asDouble(0),
              node.hasNonNull("maxLength") ? node.path("maxLength").asInt() : null,
              node.has("options")
                  ? java.util.stream.StreamSupport.stream(node.path("options").spliterator(), false)
                      .map(JsonNode::asText)
                      .toList()
                  : null));
    }
    return schemas;
  }

  private record TargetMember(String role, @Nullable UUID teamId) {}

  private record RoomParametersRow(@Nullable String parametersJson) {}

  private record TeamOccupancyRow(UUID teamId, int templateIndex, long occupied) {}

  /** {@code manifest-schema.md} § `parameters` — one entry's declared schema. */
  /**
   * {@code min}/{@code max} are primitive: manifest-schema.md requires them on every {@code
   * integer}/{@code decimal} parameter, the only types that read them, so there is no null case to
   * represent.
   */
  private record ParameterSchema(
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

  // ---------------------------------------------------------------------
  // ROOM_STATE snapshot (room-websocket.md § Snapshot Delivery, IMP-M2-05)
  // ---------------------------------------------------------------------

  /**
   * {@code version} is returned alongside, not inside, the snapshot: {@code room-websocket.md} §
   * Message Envelope carries it at the envelope level ({@code {"type","version","payload"}}), and
   * {@code ROOM_STATE}'s own payload shape does not list it as a field.
   */
  RoomStateSnapshotWithVersion roomStateSnapshot(UUID roomId, int spectatorCapacity) {
    RoomSnapshotHeader header =
        jdbcClient
            .sql(
                """
                select r.room_id, r.state, r.game_id, g.title as game_name,
                       r.spectating_enabled, r.host_user_id, r.parameters,
                       r.active_session_id, r.version
                from room r join game g on g.game_id = r.game_id
                where r.room_id = :roomId
                """)
            .param("roomId", roomId)
            .query(
                (rs, rowNum) ->
                    new RoomSnapshotHeader(
                        rs.getObject("room_id", UUID.class),
                        rs.getString("state"),
                        rs.getObject("game_id", UUID.class),
                        rs.getString("game_name"),
                        rs.getBoolean("spectating_enabled"),
                        rs.getObject("host_user_id", UUID.class),
                        rs.getString("parameters"),
                        rs.getObject("active_session_id", UUID.class),
                        rs.getLong("version")))
            .optional()
            .orElseThrow(() -> new IllegalStateException("room " + roomId + " vanished"));

    List<ParticipantRow> participants =
        jdbcClient
            .sql(
                """
                select rp.user_id, au.username, rp.role, rp.team_id, rp.ready, rp.join_order,
                       rp.connection_count,
                       ceil(greatest(0, extract(epoch from rp.grace_expires_at - now())))::bigint
                         as grace_remaining_seconds
                from room_participant rp join app_user au on au.user_id = rp.user_id
                where rp.room_id = :roomId and rp.left_at is null
                order by rp.join_order
                """)
            .param("roomId", roomId)
            .query(
                (rs, rowNum) ->
                    new ParticipantRow(
                        rs.getObject("user_id", UUID.class),
                        rs.getString("username"),
                        rs.getString("role"),
                        rs.getObject("team_id", UUID.class),
                        rs.getBoolean("ready"),
                        rs.getLong("join_order"),
                        rs.getInt("connection_count") > 0,
                        rs.getObject("grace_remaining_seconds", Long.class)))
            .list();

    List<RoomStateSnapshot.PlayerEntry> players = new ArrayList<>();
    List<RoomStateSnapshot.SpectatorEntry> spectators = new ArrayList<>();
    // room-lifecycle.md §3.4: disconnectedPlayers only lists a reservation still live
    // (grace_expires_at > now(), i.e. a non-null remaining-seconds row here), clamped to min 1 —
    // a due-but-unswept reservation is never advertised as live.
    List<RoomStateSnapshot.DisconnectedPlayerEntry> disconnected = new ArrayList<>();
    // team_id -> member ids, join-order preserved by the query's own ORDER BY above.
    Map<UUID, List<UUID>> membersByTeam = new java.util.LinkedHashMap<>();
    for (ParticipantRow row : participants) {
      if ("SPECTATOR".equals(row.role())) {
        spectators.add(new RoomStateSnapshot.SpectatorEntry(row.userId(), row.username()));
      } else {
        players.add(
            new RoomStateSnapshot.PlayerEntry(
                row.userId(),
                row.username(),
                row.teamId(),
                row.ready(),
                row.connected(),
                row.joinOrder()));
        // ceil(...) is 0 exactly when grace_expires_at <= now() (due but unswept) — excluded, per
        // §3.4: "a due-but-unswept reservation is never advertised as live." A row that clears
        // this filter is always > now(), so ceil() is already >= 1 with no separate clamp needed.
        if (row.graceRemainingSeconds() != null && row.graceRemainingSeconds() > 0) {
          disconnected.add(
              new RoomStateSnapshot.DisconnectedPlayerEntry(
                  row.userId(), row.graceRemainingSeconds().intValue()));
        }
        if (row.teamId() != null) {
          membersByTeam.computeIfAbsent(row.teamId(), id -> new ArrayList<>()).add(row.userId());
        }
      }
    }

    List<TeamTemplateInfo> templates = teamTemplatesForRoom(roomId);
    List<RoomStateSnapshot.TeamEntry> teams =
        jdbcClient
            .sql(
                """
                select team_id, template_index, team_index, configured_size
                from room_team where room_id = :roomId
                order by template_index, team_index
                """)
            .param("roomId", roomId)
            .query(
                (rs, rowNum) -> {
                  UUID teamId = rs.getObject("team_id", UUID.class);
                  int templateIndex = rs.getInt("template_index");
                  return new RoomStateSnapshot.TeamEntry(
                      teamId,
                      templates.get(templateIndex).name(),
                      rs.getInt("configured_size"),
                      rs.getInt("team_index"),
                      membersByTeam.getOrDefault(teamId, List.of()));
                })
            .list();

    Map<String, Object> parameters = readParameters(header.parametersJson());

    RoomStateSnapshot snapshot =
        new RoomStateSnapshot(
            header.roomId(),
            header.state(),
            header.gameId(),
            header.gameName(),
            header.spectatingEnabled(),
            spectatorCapacity,
            header.hostUserId(),
            players,
            List.of(),
            spectators,
            teams,
            parameters,
            disconnected,
            header.activeSessionId(),
            null);
    return new RoomStateSnapshotWithVersion(header.version(), snapshot);
  }

  private Map<String, Object> readParameters(@Nullable String parametersJson) {
    if (parametersJson == null) {
      return Map.of();
    }
    try {
      @SuppressWarnings("unchecked")
      Map<String, Object> parsed = objectMapper.readValue(parametersJson, Map.class);
      return parsed;
    } catch (Exception e) {
      throw new IllegalStateException("Stored room.parameters is not valid JSON", e);
    }
  }

  private record MemberForCommand(
      String role,
      @Nullable UUID hostUserId,
      String state,
      boolean spectatingEnabled,
      boolean supportsSpectators) {}

  private record MemberAndState(String role, String state) {}

  private record RoomHostAndState(@Nullable UUID hostUserId, String state) {}

  private record ParticipantRow(
      UUID userId,
      String username,
      String role,
      @Nullable UUID teamId,
      boolean ready,
      long joinOrder,
      boolean connected,
      @Nullable Long graceRemainingSeconds) {}

  private record RoomSnapshotHeader(
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

  // ---------------------------------------------------------------------
  // Reads (room-rest-api.md §3, §3.1, §4)
  // ---------------------------------------------------------------------

  private static final String RESOURCE_SELECT =
      """
      select r.room_id, r.game_id, g.title as game_name, r.game_version, r.state, r.visibility,
             r.host_user_id, hu.username as host_username, r.spectating_enabled, r.invite_code,
             r.version, r.created_at,
             gv.min_players, gv.max_players, gv.supports_spectators, gv.supports_mid_game_join,
             coalesce(pc.player_count, 0) as player_count,
             coalesce(cap.player_capacity, 0) as player_capacity,
             coalesce(sc.spectator_count, 0) as spectator_count
      from room r
      join game g on g.game_id = r.game_id
      join game_version gv on gv.game_id = r.game_id and gv.version = r.game_version
      left join app_user hu on hu.user_id = r.host_user_id
      left join (select room_id, count(*) as player_count from room_participant
                 where role = 'PLAYER' and left_at is null group by room_id) pc
        on pc.room_id = r.room_id
      left join (select room_id, sum(configured_size) as player_capacity from room_team
                 group by room_id) cap
        on cap.room_id = r.room_id
      left join (select room_id, count(*) as spectator_count from room_participant
                 where role = 'SPECTATOR' and left_at is null group by room_id) sc
        on sc.room_id = r.room_id
      """;

  /** Post-mutation refetch: the caller is already known to have access (they just acted on it). */
  Optional<RoomResourceRow> findRoomResource(UUID roomId) {
    return jdbcClient
        .sql(RESOURCE_SELECT + " where r.room_id = :roomId")
        .param("roomId", roomId)
        .query(RoomRepository::mapResourceRow)
        .optional();
  }

  /**
   * {@code room-rest-api.md} §4: {@code visible()} from §3, plus a current-participant carve-out —
   * necessary so a client can re-fetch (page refresh, pre-socket read) a room it already belongs
   * to, including an {@code INVITE_ONLY} one, without re-presenting the invite code. §4's own text
   * only rules out an id-based bypass of the invite code, not a membership-based one; §3's
   * predicate is written for the *browsing* case where the caller is not yet a member. Not applied
   * to listing (§3 below), where FR-LIST-007's "for any player" stays literal.
   */
  Optional<RoomResourceRow> findVisibleRoomResource(UUID roomId, UUID viewerId) {
    return jdbcClient
        .sql(
            RESOURCE_SELECT
                + """
                 where r.room_id = :roomId and r.state <> 'CLOSED'
                   and ( r.visibility = 'PUBLIC'
                      or (r.visibility = 'FRIENDS_ONLY' and exists (
                            select 1 from friendship f
                            where f.user_id_low = least(r.host_user_id, :viewerId)
                              and f.user_id_high = greatest(r.host_user_id, :viewerId)))
                      or exists (select 1 from room_participant rp
                                 where rp.room_id = r.room_id and rp.user_id = :viewerId
                                   and rp.left_at is null) )
                """)
        .param("roomId", roomId)
        .param("viewerId", viewerId)
        .query(RoomRepository::mapResourceRow)
        .optional();
  }

  /** {@code room-rest-api.md} §3.1: {@code visible()} bypassed, {@code state <> 'CLOSED'} kept. */
  Optional<RoomResourceRow> resolveInviteCode(String inviteCode) {
    return jdbcClient
        .sql(RESOURCE_SELECT + " where r.invite_code = :inviteCode and r.state <> 'CLOSED'")
        .param("inviteCode", inviteCode)
        .query(RoomRepository::mapResourceRow)
        .optional();
  }

  /** {@code room-rest-api.md} §3 {@code included()}. */
  List<RoomListRow> listRooms(UUID viewerId, RoomListFilter filter, int spectatorCapacity) {
    WhereClause where = buildListWhereClause(viewerId, filter, spectatorCapacity);
    String sql =
        """
        select r.room_id, r.game_id, g.title as game_name, hu.username as host_username,
               r.state, coalesce(pc.player_count, 0) as player_count,
               coalesce(cap.player_capacity, 0) as player_capacity,
               gv.min_players, gv.max_players, r.spectating_enabled, gv.supports_spectators,
               gv.supports_mid_game_join, coalesce(sc.spectator_count, 0) as spectator_count
        from room r
        join game g on g.game_id = r.game_id
        join game_version gv on gv.game_id = r.game_id and gv.version = r.game_version
        left join app_user hu on hu.user_id = r.host_user_id
        left join (select room_id, count(*) as player_count from room_participant
                   where role = 'PLAYER' and left_at is null group by room_id) pc
          on pc.room_id = r.room_id
        left join (select room_id, sum(configured_size) as player_capacity from room_team
                   group by room_id) cap
          on cap.room_id = r.room_id
        left join (select room_id, count(*) as spectator_count from room_participant
                   where role = 'SPECTATOR' and left_at is null group by room_id) sc
          on sc.room_id = r.room_id
        where\s"""
            + where.sql()
            + " order by "
            + orderByClause(filter.sort())
            + " limit :limit offset :offset";

    var spec = jdbcClient.sql(sql);
    for (var entry : where.params().entrySet()) {
      spec = spec.param(entry.getKey(), entry.getValue());
    }
    spec = spec.param("limit", filter.pageSize()).param("offset", filter.offset());
    return spec.query(RoomRepository::mapListRow).list();
  }

  long countRooms(UUID viewerId, RoomListFilter filter, int spectatorCapacity) {
    WhereClause where = buildListWhereClause(viewerId, filter, spectatorCapacity);
    String sql =
        """
        select count(*)
        from room r
        join game g on g.game_id = r.game_id
        join game_version gv on gv.game_id = r.game_id and gv.version = r.game_version
        left join app_user hu on hu.user_id = r.host_user_id
        left join (select room_id, count(*) as player_count from room_participant
                   where role = 'PLAYER' and left_at is null group by room_id) pc
          on pc.room_id = r.room_id
        left join (select room_id, sum(configured_size) as player_capacity from room_team
                   group by room_id) cap
          on cap.room_id = r.room_id
        left join (select room_id, count(*) as spectator_count from room_participant
                   where role = 'SPECTATOR' and left_at is null group by room_id) sc
          on sc.room_id = r.room_id
        where\s"""
            + where.sql();
    var spec = jdbcClient.sql(sql);
    for (var entry : where.params().entrySet()) {
      spec = spec.param(entry.getKey(), entry.getValue());
    }
    Long count = spec.query(Long.class).single();
    return count == null ? 0 : count;
  }

  private WhereClause buildListWhereClause(
      UUID viewerId, RoomListFilter filter, int spectatorCapacity) {
    Map<String, Object> params = new java.util.LinkedHashMap<>();
    StringBuilder sql = new StringBuilder();
    sql.append(
        """
        r.state <> 'CLOSED'
        and ( r.visibility = 'PUBLIC'
           or (r.visibility = 'FRIENDS_ONLY' and exists (
                 select 1 from friendship f
                 where f.user_id_low = least(r.host_user_id, :viewerId)
                   and f.user_id_high = greatest(r.host_user_id, :viewerId))) )
        and ( ( coalesce(pc.player_count, 0) < coalesce(cap.player_capacity, 0)
                and (r.state = 'LOBBY' or gv.supports_mid_game_join) )
           or ( r.spectating_enabled and gv.supports_spectators
                and coalesce(sc.spectator_count, 0) < :spectatorCapacity ) )
        """);
    params.put("viewerId", viewerId);
    params.put("spectatorCapacity", spectatorCapacity);

    if (filter.search() != null && !filter.search().isBlank()) {
      sql.append(" and (lower(g.title) like :search or lower(hu.username) like :search)\n");
      params.put("search", "%" + filter.search().toLowerCase(java.util.Locale.ROOT) + "%");
    }
    if (filter.statuses() != null && !filter.statuses().isEmpty()) {
      sql.append(" and r.state in (:statuses)\n");
      params.put("statuses", filter.statuses());
    }
    if (filter.minPlayers() != null) {
      sql.append(" and gv.max_players >= :minPlayers\n");
      params.put("minPlayers", filter.minPlayers());
    }
    if (filter.maxPlayers() != null) {
      sql.append(" and gv.min_players <= :maxPlayers\n");
      params.put("maxPlayers", filter.maxPlayers());
    }
    if (filter.playerCountMin() != null) {
      sql.append(" and coalesce(pc.player_count, 0) >= :playerCountMin\n");
      params.put("playerCountMin", filter.playerCountMin());
    }
    if (filter.playerCountMax() != null) {
      sql.append(" and coalesce(pc.player_count, 0) <= :playerCountMax\n");
      params.put("playerCountMax", filter.playerCountMax());
    }
    return new WhereClause(sql.toString(), params);
  }

  private static final Map<String, String> SORT_COLUMNS =
      Map.of(
          "gameName", "g.title",
          "minPlayers", "gv.min_players",
          "maxPlayers", "gv.max_players",
          "playerCount", "coalesce(pc.player_count, 0)",
          "status", "r.state");

  /**
   * {@code pagination-filtering-sorting.md} § Offset pagination: a total ordering, sort column plus
   * primary key, so identical sort values never drift non-deterministically between requests.
   */
  private static String orderByClause(Sort sort) {
    if (sort.isUnsorted()) {
      return "r.created_at desc, r.room_id";
    }
    List<String> clauses = new ArrayList<>();
    for (Sort.Order order : sort) {
      String column = SORT_COLUMNS.get(order.getProperty());
      if (column == null) {
        throw new IllegalStateException(
            "Unvalidated sort field reached the repository: " + order.getProperty());
      }
      clauses.add(column + (order.isDescending() ? " desc" : " asc"));
    }
    clauses.add("r.room_id");
    return String.join(", ", clauses);
  }

  private boolean isVisible(String visibility, @Nullable UUID hostUserId, UUID viewerId) {
    if ("PUBLIC".equals(visibility)) {
      return true;
    }
    if ("FRIENDS_ONLY".equals(visibility) && hostUserId != null) {
      if (hostUserId.equals(viewerId)) {
        return true;
      }
      Boolean friends =
          jdbcClient
              .sql(
                  "select exists(select 1 from friendship where user_id_low = least(:a, :b) and"
                      + " user_id_high = greatest(:a, :b))")
              .param("a", hostUserId)
              .param("b", viewerId)
              .query(Boolean.class)
              .single();
      return Boolean.TRUE.equals(friends);
    }
    return false;
  }

  private List<TeamTemplateInfo> parseTeamTemplates(@Nullable String manifestJson) {
    if (manifestJson == null) {
      throw new IllegalStateException("An ACTIVE game_version must carry a validated manifest.");
    }
    JsonNode manifest;
    try {
      manifest = objectMapper.readTree(manifestJson);
    } catch (Exception e) {
      throw new IllegalStateException("Stored manifest is not valid JSON", e);
    }
    List<TeamTemplateInfo> templates = new ArrayList<>();
    for (JsonNode templateNode : manifest.path("teamTemplates")) {
      JsonNode size = templateNode.path("size");
      JsonNode count = templateNode.path("count");
      // manifest-schema.md § teamTemplates: name defaults to "Team" for a single template.
      templates.add(
          new TeamTemplateInfo(
              templateNode.path("name").asText("Team"),
              size.path("min").asInt(),
              size.path("max").asInt(),
              count.path("min").asInt(),
              count.path("max").asInt()));
    }
    return templates;
  }

  /** {@code IMP-M2-05} — team management commands need the manifest's fuller template shape. */
  List<TeamTemplateInfo> teamTemplatesForRoom(UUID roomId) {
    String manifest =
        jdbcClient
            .sql(
                """
                select gv.manifest
                from room r join game_version gv
                  on gv.game_id = r.game_id and gv.version = r.game_version
                where r.room_id = :roomId
                """)
            .param("roomId", roomId)
            .query(String.class)
            .single();
    return parseTeamTemplates(manifest);
  }

  private static CreatableGameCandidate mapCreatableGameCandidate(ResultSet rs, int rowNum)
      throws SQLException {
    UUID gameId = rs.getObject("game_id", UUID.class);
    UUID ownerUserId = rs.getObject("owner_user_id", UUID.class);
    boolean deleted = rs.getObject("deleted_at", Timestamp.class) != null;
    boolean archived = rs.getObject("archived_at", Timestamp.class) != null;
    boolean listed = rs.getBoolean("listed");
    String badge = rs.getString("badge");
    CreatableGame game =
        new CreatableGame(
            gameId,
            rs.getString("title"),
            rs.getString("version"),
            rs.getString("manifest"),
            (Integer) rs.getObject("min_players"),
            (Integer) rs.getObject("max_players"),
            rs.getBoolean("supports_spectators"),
            rs.getBoolean("supports_mid_game_join"));
    return new CreatableGameCandidate(game, ownerUserId, deleted, archived, listed, badge);
  }

  private static RoomForJoin mapRoomForJoin(ResultSet rs, int rowNum) throws SQLException {
    return new RoomForJoin(
        rs.getObject("room_id", UUID.class),
        rs.getString("visibility"),
        rs.getString("state"),
        rs.getBoolean("spectating_enabled"),
        rs.getObject("host_user_id", UUID.class),
        rs.getString("invite_code"),
        rs.getBoolean("supports_spectators"),
        rs.getBoolean("supports_mid_game_join"));
  }

  private static RoomResourceRow mapResourceRow(ResultSet rs, int rowNum) throws SQLException {
    return new RoomResourceRow(
        rs.getObject("room_id", UUID.class),
        rs.getObject("game_id", UUID.class),
        rs.getString("game_name"),
        rs.getString("game_version"),
        rs.getString("state"),
        rs.getString("visibility"),
        rs.getObject("host_user_id", UUID.class),
        rs.getString("host_username"),
        rs.getBoolean("spectating_enabled"),
        rs.getString("invite_code"),
        rs.getLong("version"),
        requireNonNull(rs.getObject("created_at", Timestamp.class)).toInstant(),
        (Integer) rs.getObject("min_players"),
        (Integer) rs.getObject("max_players"),
        rs.getBoolean("supports_spectators"),
        rs.getBoolean("supports_mid_game_join"),
        rs.getLong("player_count"),
        rs.getLong("player_capacity"),
        rs.getLong("spectator_count"));
  }

  private static RoomListRow mapListRow(ResultSet rs, int rowNum) throws SQLException {
    return new RoomListRow(
        rs.getObject("room_id", UUID.class),
        rs.getObject("game_id", UUID.class),
        rs.getString("game_name"),
        rs.getString("host_username"),
        rs.getString("state"),
        rs.getLong("player_count"),
        rs.getLong("player_capacity"),
        (Integer) rs.getObject("min_players"),
        (Integer) rs.getObject("max_players"),
        rs.getBoolean("spectating_enabled"),
        rs.getBoolean("supports_spectators"),
        rs.getBoolean("supports_mid_game_join"),
        rs.getLong("spectator_count"));
  }

  private static <T> T requireNonNull(@Nullable T value) {
    if (value == null) {
      throw new IllegalStateException("Expected column value was null");
    }
    return value;
  }

  private record WhereClause(String sql, Map<String, Object> params) {}

  record TeamTemplateInfo(String name, int sizeMin, int sizeMax, int countMin, int countMax) {}

  private record CreatableGameCandidate(
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

  private record RoomForJoin(
      UUID roomId,
      String visibility,
      String state,
      boolean spectatingEnabled,
      @Nullable UUID hostUserId,
      String inviteCode,
      boolean supportsSpectators,
      boolean supportsMidGameJoin) {}

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

  // ---------------------------------------------------------------------
  // Disconnect (IMP-M2-07, room-lifecycle.md §3.1 / flows/04-disconnect-and-return.md §A).
  // ---------------------------------------------------------------------

  /**
   * Flow A steps 3-5: decrement, stop if still connected, else branch by room state and role.
   * Always runs on the owning instance (a STOMP session only exists after subscribe-time ownership
   * verification), so a Lobby/spectator departure announces inline and an In-Progress player
   * departure opens a reservation, both under {@link RoomEventExecutor}'s lock the caller already
   * holds.
   */
  DisconnectOutcome registerDisconnect(UUID roomId, UUID userId, Duration gracePeriod) {
    Optional<Integer> remaining =
        jdbcClient
            .sql(
                """
                update room_participant set connection_count = connection_count - 1
                 where room_id = :roomId and user_id = :userId
                   and left_at is null and connection_count > 0
                returning connection_count
                """)
            .param("roomId", roomId)
            .param("userId", userId)
            .query(Integer.class)
            .optional();
    if (remaining.isEmpty() || remaining.get() > 0) {
      // Empty: already left, or a duplicate disconnect event for an already-processed drop —
      // either way, nothing to do. > 0: another of this user's tabs is still open (FR-GRACE-006).
      return new DisconnectOutcome.StillConnected();
    }

    record RoomForDisconnect(String state, @Nullable UUID hostUserId) {}
    RoomForDisconnect room =
        jdbcClient
            .sql("select state, host_user_id from room where room_id = :roomId")
            .param("roomId", roomId)
            .query(
                (rs, n) ->
                    new RoomForDisconnect(
                        rs.getString("state"), rs.getObject("host_user_id", UUID.class)))
            .single();
    String role =
        jdbcClient
            .sql(
                "select role from room_participant"
                    + " where room_id = :roomId and user_id = :userId and left_at is null")
            .param("roomId", roomId)
            .param("userId", userId)
            .query(String.class)
            .single();

    if (!"IN_PROGRESS".equals(room.state()) || "SPECTATOR".equals(role)) {
      // FR-DC-002 (Lobby) / FR-DC-003 (spectator, any state): immediate leave, no reservation.
      return leaveRoom(userId, roomId, true)
          .<DisconnectOutcome>map(DisconnectOutcome.LeftImmediately::new)
          .orElse(new DisconnectOutcome.StillConnected());
    }

    // IN_PROGRESS, PLAYER: open a grace reservation (§3.1). Guarded on grace_reservation_id IS
    // NULL so a duplicate disconnect event (connection_count already decremented once but this
    // method re-entered before the first call's own transaction visibly committed) never resets
    // an already-running window.
    jdbcClient
        .sql(
            """
            update room_participant
               set grace_reservation_id = gen_random_uuid(),
                   grace_expires_at = now() + make_interval(secs => :graceSeconds)
             where room_id = :roomId and user_id = :userId
               and left_at is null and grace_reservation_id is null
            """)
        .param("roomId", roomId)
        .param("userId", userId)
        .param("graceSeconds", (int) gracePeriod.toSeconds())
        .update();
    boolean wasHost = userId.equals(room.hostUserId());
    return new DisconnectOutcome.GraceOpened(
        new PlayerDisconnectedPayload(userId, gracePeriod.toSeconds()), wasHost);
  }

  sealed interface DisconnectOutcome {
    record StillConnected() implements DisconnectOutcome {}

    record LeftImmediately(PlayerLeftPayload payload) implements DisconnectOutcome {}

    record GraceOpened(PlayerDisconnectedPayload payload, boolean wasHost)
        implements DisconnectOutcome {}
  }

  /** {@code room-websocket.md} `PLAYER_DISCONNECTED` payload shape. */
  record PlayerDisconnectedPayload(UUID playerId, long gracePeriodSeconds) {}

  // ---------------------------------------------------------------------
  // Host transfer helpers, shared by disconnect (FR-HDISC-001), the migration-window sweep duty
  // (FR-HDISC-004) and the deferred-tail duty.
  // ---------------------------------------------------------------------

  /**
   * {@code room-lifecycle.md} §3.1's host-transfer note: skip while the migration window is open.
   */
  boolean migrationWindowOpen(UUID roomId) {
    Boolean open =
        jdbcClient
            .sql(
                "select migration_window_expires_at is not null and migration_window_expires_at > now()"
                    + " from room where room_id = :roomId")
            .param("roomId", roomId)
            .query(Boolean.class)
            .single();
    return Boolean.TRUE.equals(open);
  }

  /** FR-XFER-001's tie-break: the earliest-joined currently-connected human player, if any. */
  Optional<UUID> nextConnectedHumanExcluding(UUID roomId, @Nullable UUID excludeUserId) {
    return jdbcClient
        .sql(
            """
            select user_id from room_participant
             where room_id = :roomId and role = 'PLAYER' and left_at is null
               and connection_count > 0
               and (:excludeUserId::uuid is null or user_id <> :excludeUserId)
             order by join_order asc limit 1
            """)
        .param("roomId", roomId)
        .param("excludeUserId", excludeUserId)
        .query(UUID.class)
        .optional();
  }

  /**
   * {@code room-lifecycle.md} §3.2's reconnect mirror of {@link #registerDisconnect}'s decrement
   * (`IMP-M2-08`): a session subscribing to the room's topic for the first time
   * (`room.RoomSubscriptionAuthorizer`) establishes presence, and — guarded on an outstanding grace
   * reservation, exactly the statement §3.2 gives — resolves it atomically with the same increment.
   * Rowcount {@code 1} on the guarded update means this caller won the race and owns the {@code
   * PLAYER_RECONNECTED} broadcast; {@code 0} means there was nothing to resolve (an ordinary first
   * connection has no reservation at all), so a plain increment applies instead. Fenced — unlike
   * the old unconditional increment, this can produce a version-bumping event, so it must run
   * inside {@link RoomEventExecutor}'s lock like every other mutation that might publish one.
   */
  ReconnectOutcome resolveConnect(UUID roomId, UUID userId) {
    int reconnected =
        jdbcClient
            .sql(
                """
                update room_participant
                   set connection_count = connection_count + 1,
                       grace_reservation_id = null, grace_expires_at = null
                 where room_id = :roomId and user_id = :userId
                   and left_at is null and grace_reservation_id is not null
                """)
            .param("roomId", roomId)
            .param("userId", userId)
            .update();
    if (reconnected > 0) {
      return new ReconnectOutcome.Reconnected(new PlayerReconnectedPayload(userId));
    }
    jdbcClient
        .sql(
            "update room_participant set connection_count = connection_count + 1"
                + " where room_id = :roomId and user_id = :userId and left_at is null")
        .param("roomId", roomId)
        .param("userId", userId)
        .update();
    return new ReconnectOutcome.OrdinaryConnect();
  }

  sealed interface ReconnectOutcome {
    record Reconnected(PlayerReconnectedPayload payload) implements ReconnectOutcome {}

    record OrdinaryConnect() implements ReconnectOutcome {}
  }

  /** {@code room-websocket.md} `PLAYER_RECONNECTED` payload shape, verbatim field-for-field. */
  record PlayerReconnectedPayload(UUID playerId) {}

  /**
   * {@code room-websocket.md} Authorization Model, {@code IMP-GATE-51}: a {@code SUBSCRIBE} from a
   * non-member is a stranger to the room in the ordinary case (silent drop), but {@code
   * data-model.md} §2's row-reuse rule (`IMP-GATE-50`) means this caller's own history in the room
   * — if any — lives in exactly one row, so its {@code leave_reason} is inspectable. {@code
   * GRACE_EXPIRED} specifically means the caller lost the race to their own grace expiry, which is
   * told to the client (FR-REJOIN-007) rather than left to read as "room not found." Every other
   * terminal reason is an intentional past departure and stays a silent drop.
   */
  boolean lostRaceToOwnExpiry(UUID roomId, UUID userId) {
    Boolean lost =
        jdbcClient
            .sql(
                "select left_at is not null and leave_reason = 'GRACE_EXPIRED'"
                    + " from room_participant where room_id = :roomId and user_id = :userId")
            .param("roomId", roomId)
            .param("userId", userId)
            .query(Boolean.class)
            .optional()
            .orElse(false);
    return Boolean.TRUE.equals(lost);
  }

  boolean isConnected(UUID roomId, UUID userId) {
    Boolean connected =
        jdbcClient
            .sql(
                "select connection_count > 0 from room_participant"
                    + " where room_id = :roomId and user_id = :userId and left_at is null")
            .param("roomId", roomId)
            .param("userId", userId)
            .query(Boolean.class)
            .optional()
            .orElse(false);
    return Boolean.TRUE.equals(connected);
  }

  HostTransferredPayload transferHostAutomatically(
      UUID roomId, UUID newHostId, @Nullable UUID previousHostId, String reason) {
    jdbcClient
        .sql("update room set host_user_id = :newHostId where room_id = :roomId")
        .param("newHostId", newHostId)
        .param("roomId", roomId)
        .update();
    return new HostTransferredPayload(newHostId, previousHostId, reason);
  }

  void clearMigrationWindow(UUID roomId) {
    jdbcClient
        .sql("update room set migration_window_expires_at = null where room_id = :roomId")
        .param("roomId", roomId)
        .update();
  }

  // ---------------------------------------------------------------------
  // The deadline sweep (IMP-M2-07, room-lifecycle.md §5.1).
  // ---------------------------------------------------------------------

  /** §5.1 duty 1's query: this instance's own due reservations. */
  List<DueReservation> dueGraceReservations(String instanceId) {
    return jdbcClient
        .sql(
            """
            select rp.room_id, rp.user_id, rp.grace_reservation_id, rp.team_id,
                   r.active_session_id
              from room_participant rp join room r using (room_id)
             where rp.grace_expires_at <= now() and rp.left_at is null
               and r.owner_instance_id = :instanceId
            """)
        .param("instanceId", instanceId)
        .query(
            (rs, n) ->
                new DueReservation(
                    rs.getObject("room_id", UUID.class),
                    rs.getObject("user_id", UUID.class),
                    rs.getObject("grace_reservation_id", UUID.class),
                    rs.getObject("team_id", UUID.class),
                    rs.getObject("active_session_id", UUID.class)))
        .list();
  }

  record DueReservation(
      UUID roomId,
      UUID userId,
      UUID reservationId,
      @Nullable UUID teamId,
      @Nullable UUID activeSessionId) {}

  /**
   * {@code room-lifecycle.md} §3.2's resolve statement, guarded for the expiry path (`:dueCheck =
   * true`) with {@code announceInline} always {@code true} — the sweep only ever resolves rows on
   * rooms it owns (duty 1's own query already filters on that). §3.3 step 2's conditional outbox
   * insert runs in the same transaction on a win.
   *
   * @return present iff this call's rowcount was 1 (this caller won and owns the side effects).
   */
  Optional<ResolvedReservation> resolveExpiredReservation(DueReservation due) {
    // A list, not .optional() on a single nullable column: RETURNING team_id can legitimately
    // return a row whose team_id is itself null, which .optional() cannot distinguish from "no
    // row matched" — the rowcount, not the value, is the entire idempotency story (§3.2).
    List<@Nullable UUID> rows =
        jdbcClient
            .sql(
                """
                update room_participant
                   set left_at = now(), leave_reason = 'GRACE_EXPIRED',
                       grace_reservation_id = null, grace_expires_at = null,
                       lost_announced_at = now()
                 where room_id = :roomId and user_id = :userId
                   and grace_reservation_id = :reservationId
                   and left_at is null and grace_expires_at <= now()
                returning team_id
                """)
            .param("roomId", due.roomId())
            .param("userId", due.userId())
            .param("reservationId", due.reservationId())
            .query(UUID.class)
            .list();
    if (rows.isEmpty()) {
      return Optional.empty();
    }
    UUID teamId = rows.get(0);
    if (due.activeSessionId() != null) {
      insertPlayerLeftOutbox(due.roomId(), due.userId(), teamId, "grace_period_expired");
    }
    return Optional.of(new ResolvedReservation(teamId));
  }

  record ResolvedReservation(@Nullable UUID teamId) {}

  /** {@code room-websocket.md} `PLAYER_LOST` payload shape. */
  record PlayerLostPayload(UUID playerId, @Nullable UUID teamId, String reason) {}

  /**
   * §3.3 step 4 / §5.1 duty 2's shared closure predicate: no participant remains with {@code
   * left_at IS NULL AND role = 'PLAYER'} — a participant still in grace counts as present.
   */
  boolean anyHumanRemains(UUID roomId) {
    Boolean exists =
        jdbcClient
            .sql(
                "select exists(select 1 from room_participant"
                    + " where room_id = :roomId and role = 'PLAYER' and left_at is null)")
            .param("roomId", roomId)
            .query(Boolean.class)
            .single();
    return Boolean.TRUE.equals(exists);
  }

  @Nullable UUID activeSessionId(UUID roomId) {
    return jdbcClient
        .sql("select active_session_id from room where room_id = :roomId")
        .param("roomId", roomId)
        .query(UUID.class)
        .optional()
        .orElse(null);
  }

  // ---------------------------------------------------------------------
  // Detaching a session (room-lifecycle.md §4, IMP-M2-09, IMP-GATE-49) — one method, five
  // callers; this repository method is steps 1-6, always invoked from inside RoomEventExecutor's
  // lock and transaction, matching resolveExpiredReservation's own precedent of relying on that
  // wrapping transaction rather than declaring @Transactional itself. Step 3's version bump and
  // epoch fence are RoomEventExecutor's own follow-up statement, the same split changeParameters
  // and transferHost already use for a room-column mutation with no CAS of its own — the shared
  // transaction rolls both back together if the fenced commit loses the race.
  // ---------------------------------------------------------------------

  /**
   * @param sessionId the session the caller believes is still attached; {@code null} for a room
   *     with none (a Lobby host-leave). Step 2's comparison against the row's actual {@code
   *     active_session_id} — null-safe, so two nulls count as a match — is the whole idempotency
   *     story: a second caller racing to detach the same session, or one arriving after another
   *     already did, is a safe no-op.
   */
  DetachOutcome detachSession(
      UUID roomId, @Nullable UUID sessionId, String reason, String nextState) {
    // A list, not .optional() on a single nullable column, for the same reason
    // resolveExpiredReservation is: a Lobby room's active_session_id is legitimately null, which
    // .optional() cannot distinguish from "no row matched" (the room actually vanished).
    List<@Nullable UUID> rows =
        jdbcClient
            .sql("select active_session_id from room where room_id = :roomId for update")
            .param("roomId", roomId)
            .query(UUID.class)
            .list();
    if (rows.isEmpty()) {
      throw new IllegalStateException("room " + roomId + " vanished");
    }
    UUID currentSessionId = rows.get(0);
    if (!java.util.Objects.equals(currentSessionId, sessionId)) {
      return new DetachOutcome(false);
    }
    jdbcClient
        .sql(
            """
            update room
               set state = :nextState, active_session_id = null,
                   closed_at = case when :nextState = 'CLOSED' then now() end,
                   closed_reason = case when :nextState = 'CLOSED' then :reason end
             where room_id = :roomId
            """)
        .param("nextState", nextState)
        .param("reason", reason)
        .param("roomId", roomId)
        .update();
    if ("CLOSED".equals(nextState)) {
      // FR-INV-008: deleted at closure, not left to the room_invitation_room_id_fkey cascade,
      // which only fires seven days later at FR-JOB-009's purge (data-model.md §1 Closure
      // reasons).
      jdbcClient
          .sql("delete from room_invitation where room_id = :roomId")
          .param("roomId", roomId)
          .update();
    }
    if (sessionId != null) {
      // ADR-0010: the owed teardown is a row keyed by sessionId, never a room-level flag, and any
      // undelivered game input for that session is now moot (data-model.md §4.2).
      jdbcClient
          .sql(
              "insert into session_teardown (session_id, room_id, reason)"
                  + " values (:sessionId, :roomId, :reason) on conflict (session_id) do nothing")
          .param("sessionId", sessionId)
          .param("roomId", roomId)
          .param("reason", reason)
          .update();
      jdbcClient
          .sql(
              "delete from game_input_outbox"
                  + " where session_id = :sessionId and dispatched_at is null")
          .param("sessionId", sessionId)
          .update();
    }
    return new DetachOutcome(true);
  }

  /** {@code true} iff this call actually performed the detach (step 2's idempotency check). */
  record DetachOutcome(boolean detached) {}

  /** {@code room-websocket.md} `ROOM_CLOSED` payload shape. */
  record RoomClosedPayload(String reason) {}

  /** {@code room-websocket.md} `GAME_ENDED` payload shape, shared with FR-JOB-007c's trigger. */
  record GameEndedPayload(UUID roomId, boolean readinessReset) {}

  /**
   * FR-JOB-007c / {@code room-lifecycle.md} §5.2: "clean keys, reset readiness, notify
   * participants" — the readiness half, applied to every current participant of the room, not just
   * players (a spectator has no {@code ready} value that means anything, but resetting it is
   * harmless and avoids a role-conditional statement for no observable difference).
   */
  void resetReadiness(UUID roomId) {
    jdbcClient
        .sql(
            "update room_participant set ready = false where room_id = :roomId and left_at is null")
        .param("roomId", roomId)
        .update();
  }

  /** {@link RoomService#leave}'s own outcome: the session it closed, if closure was triggered. */
  record LeaveOutcome(@Nullable UUID closedSessionId) {}

  /** §5.1 duty 2's query: departures a foreign instance resolved, still owed an announcement. */
  List<DeferredTail> deferredTails(String instanceId) {
    return jdbcClient
        .sql(
            """
            select rp.room_id, rp.user_id, rp.team_id, rp.leave_reason, r.host_user_id
              from room_participant rp join room r using (room_id)
             where rp.left_at is not null and rp.lost_announced_at is null
               and rp.leave_reason in ('GRACE_EXPIRED', 'EARLY_RELEASE', 'LEFT')
               and r.owner_instance_id = :instanceId
            """)
        .param("instanceId", instanceId)
        .query(
            (rs, n) ->
                new DeferredTail(
                    rs.getObject("room_id", UUID.class),
                    rs.getObject("user_id", UUID.class),
                    rs.getObject("team_id", UUID.class),
                    rs.getString("leave_reason"),
                    rs.getObject("host_user_id", UUID.class)))
        .list();
  }

  record DeferredTail(
      UUID roomId,
      UUID userId,
      @Nullable UUID teamId,
      String leaveReason,
      @Nullable UUID hostUserId) {}

  /**
   * §5.1 duty 2's announcement half: stamp {@code lost_announced_at}, guarded so a row another
   * concurrent tick already announced is a no-op.
   */
  boolean announceDeferredTail(UUID roomId, UUID userId) {
    int updated =
        jdbcClient
            .sql(
                "update room_participant set lost_announced_at = now()"
                    + " where room_id = :roomId and user_id = :userId and lost_announced_at is null")
            .param("roomId", roomId)
            .param("userId", userId)
            .update();
    return updated > 0;
  }

  /** §5.1 duty 3's query: elapsed migration windows on rooms this instance owns. */
  List<UUID> elapsedMigrationWindowRoomIds(String instanceId) {
    return jdbcClient
        .sql(
            "select room_id from room"
                + " where migration_window_expires_at <= now() and owner_instance_id = :instanceId")
        .param("instanceId", instanceId)
        .query(UUID.class)
        .list();
  }

  @Nullable UUID hostUserIdOf(UUID roomId) {
    return jdbcClient
        .sql("select host_user_id from room where room_id = :roomId")
        .param("roomId", roomId)
        .query(UUID.class)
        .optional()
        .orElse(null);
  }

  // ---------------------------------------------------------------------
  // Outbox drain (§5.1 duty 4, data-model.md §4.2). Not ownership-partitioned — a gRPC call, not a
  // room-topic broadcast, needs no claim.
  // ---------------------------------------------------------------------

  record OutboxRow(long id, UUID sessionId, @Nullable Long sequence, String payloadJson) {}

  List<OutboxRow> pendingOutboxRows(int limit) {
    return jdbcClient
        .sql(
            """
            select id, session_id, sequence, payload::text as payload
              from game_input_outbox
             where dispatched_at is null
             order by id
             limit :limit
             for update skip locked
            """)
        .param("limit", limit)
        .query(
            (rs, n) ->
                new OutboxRow(
                    rs.getLong("id"),
                    rs.getObject("session_id", UUID.class),
                    rs.getObject("sequence") == null ? null : rs.getLong("sequence"),
                    rs.getString("payload")))
        .list();
  }

  /**
   * Allocates the row's {@code sequence} once, per {@code data-model.md} §4.2 — never re-rolled.
   */
  long allocateOutboxSequence(long outboxRowId, long sequence) {
    jdbcClient
        .sql(
            "update game_input_outbox set sequence = :sequence where id = :id and sequence is null")
        .param("sequence", sequence)
        .param("id", outboxRowId)
        .update();
    return jdbcClient
        .sql("select sequence from game_input_outbox where id = :id")
        .param("id", outboxRowId)
        .query(Long.class)
        .single();
  }

  void markOutboxDispatched(long outboxRowId) {
    jdbcClient
        .sql("update game_input_outbox set dispatched_at = now() where id = :id")
        .param("id", outboxRowId)
        .update();
  }

  void recordOutboxAttemptFailure(long outboxRowId, String error) {
    jdbcClient
        .sql(
            "update game_input_outbox set attempts = attempts + 1, last_error = :error"
                + " where id = :id")
        .param("error", error)
        .param("id", outboxRowId)
        .update();
  }

  // ---------------------------------------------------------------------
  // Reconciliation (IMP-M2-10, room-lifecycle.md §5.2/§5.3/§5.4) — ReconciliationJob's five duties
  // and the closed-room purge.
  // ---------------------------------------------------------------------

  /** Duty (a)'s query: owed teardowns due for a re-drive attempt. */
  List<DueTeardown> dueSessionTeardowns(int limit) {
    return jdbcClient
        .sql(
            """
            select session_id, room_id, reason from session_teardown
             where next_attempt_at <= now()
             order by next_attempt_at
             limit :limit
             for update skip locked
            """)
        .param("limit", limit)
        .query(
            (rs, n) ->
                new DueTeardown(
                    rs.getObject("session_id", UUID.class),
                    rs.getObject("room_id", UUID.class),
                    rs.getString("reason")))
        .list();
  }

  record DueTeardown(UUID sessionId, UUID roomId, String reason) {}

  /** {@code turnbase.teardown.owed} (§6): outstanding teardown debt, independent of due-ness. */
  long countSessionTeardownOwed() {
    Long count = jdbcClient.sql("select count(*) from session_teardown").query(Long.class).single();
    return count == null ? 0 : count;
  }

  /**
   * {@code room-lifecycle.md} §4's {@code terminateAndCleanup} step (c), moved here from either
   * caller per the pseudocode's own placement: {@code attempts++}, {@code last_error}, and a
   * backoff of 10s doubling to a 5-minute cap, computed from the row's pre-update {@code attempts}
   * so the very first failure backs off 10s, the second 20s, and so on.
   */
  void recordSessionTeardownFailure(UUID sessionId, String error) {
    jdbcClient
        .sql(
            """
            update session_teardown
               set attempts = attempts + 1,
                   last_error = :error,
                   next_attempt_at = now()
                     + make_interval(secs => least(10 * power(2, attempts), 300)::double precision)
             where session_id = :sessionId
            """)
        .param("error", error)
        .param("sessionId", sessionId)
        .update();
  }

  /**
   * Duty (b)'s health check: a session is orphaned unless a non-closed room still names it as
   * {@code active_session_id} (`room-lifecycle.md` §5.2 — "whose room no longer exists or is
   * closed").
   */
  boolean sessionBelongsToOpenRoom(UUID sessionId) {
    Boolean exists =
        jdbcClient
            .sql(
                "select exists(select 1 from room"
                    + " where active_session_id = :sessionId and state <> 'CLOSED')")
            .param("sessionId", sessionId)
            .query(Boolean.class)
            .single();
    return Boolean.TRUE.equals(exists);
  }

  /** {@code data-model.md} §4.3's first-observation insert, guarded so a repeat is a no-op. */
  void flagOrphanedSessionIfAbsent(UUID sessionId) {
    jdbcClient
        .sql(
            "insert into orphaned_session_flag (session_id) values (:sessionId)"
                + " on conflict (session_id) do nothing")
        .param("sessionId", sessionId)
        .update();
  }

  Optional<Instant> orphanedSessionFirstObservedAt(UUID sessionId) {
    return jdbcClient
        .sql("select first_observed_at from orphaned_session_flag where session_id = :sessionId")
        .param("sessionId", sessionId)
        .query(Timestamp.class)
        .optional()
        .map(Timestamp::toInstant);
  }

  void clearOrphanedSessionFlag(UUID sessionId) {
    jdbcClient
        .sql("delete from orphaned_session_flag where session_id = :sessionId")
        .param("sessionId", sessionId)
        .update();
  }

  /**
   * Duty (c)'s candidates: an {@code IN_PROGRESS} room still naming a session. {@link
   * ReconciliationJob} checks the Game Host's own {@code session:{sessionId}} Redis record for each
   * — "no Game Host probe" (`room-lifecycle.md` §5.2): the record is the source of truth.
   */
  List<RoomSessionPair> inProgressRoomsWithSession() {
    return jdbcClient
        .sql(
            "select room_id, active_session_id from room"
                + " where state = 'IN_PROGRESS' and active_session_id is not null"
                + " for update skip locked")
        .query(
            (rs, n) ->
                new RoomSessionPair(
                    rs.getObject("room_id", UUID.class),
                    rs.getObject("active_session_id", UUID.class)))
        .list();
  }

  record RoomSessionPair(UUID roomId, UUID sessionId) {}

  /** Duty (d)'s candidates: an idle Lobby room, past the configured idle window. */
  List<UUID> idleLobbyRoomIds(Duration idleWindow) {
    return jdbcClient
        .sql(
            """
            select room_id from room r
             where state = 'LOBBY'
               and last_activity_at <= now() - make_interval(secs => :idleSeconds)
               and not exists (select 1 from room_participant rp
                                where rp.room_id = r.room_id and rp.connection_count > 0
                                  and rp.left_at is null)
             for update skip locked
            """)
        .param("idleSeconds", (int) idleWindow.toSeconds())
        .query(UUID.class)
        .list();
  }

  /** §5.4 guard 2, shared by duties (c)/(d): first observation, guarded against overwriting it. */
  Optional<Instant> roomReconcileFlaggedAt(UUID roomId) {
    return jdbcClient
        .sql("select reconcile_flagged_at from room where room_id = :roomId")
        .param("roomId", roomId)
        .query(Timestamp.class)
        .optional()
        .map(Timestamp::toInstant);
  }

  void flagRoomForReconcileIfAbsent(UUID roomId) {
    jdbcClient
        .sql(
            "update room set reconcile_flagged_at = now()"
                + " where room_id = :roomId and reconcile_flagged_at is null")
        .param("roomId", roomId)
        .update();
  }

  /** Cleared "as soon as the room looks healthy" (§5.4 guard 2) — never left dangling. */
  void clearRoomReconcileFlag(UUID roomId) {
    jdbcClient
        .sql("update room set reconcile_flagged_at = null where room_id = :roomId")
        .param("roomId", roomId)
        .update();
  }

  /** Duty (e)'s first pass: every instance currently named as an owner of a live room. */
  List<String> distinctLiveOwnerInstanceIds() {
    return jdbcClient
        .sql(
            "select distinct owner_instance_id from room"
                + " where owner_instance_id is not null and state <> 'CLOSED'")
        .query(String.class)
        .list();
  }

  /**
   * Duty (e)'s candidates (`room-lifecycle.md` §5.2): owned by a now-dead instance, or released and
   * still unclaimed (`ownerless_since` set by §1.3's graceful release).
   */
  List<UUID> unownedRoomIds(List<String> deadOwnerInstanceIds) {
    if (deadOwnerInstanceIds.isEmpty()) {
      return jdbcClient
          .sql(
              "select room_id from room where state <> 'CLOSED'"
                  + " and owner_instance_id is null and ownerless_since is not null"
                  + " for update skip locked")
          .query(UUID.class)
          .list();
    }
    return jdbcClient
        .sql(
            """
            select room_id from room
             where state <> 'CLOSED'
               and ( owner_instance_id in (:deadOwners)
                  or (owner_instance_id is null and ownerless_since is not null) )
             for update skip locked
            """)
        .param("deadOwners", deadOwnerInstanceIds)
        .query(UUID.class)
        .list();
  }

  /** FR-JOB-009 / §5.3: rooms Closed past the retention window; participant/team/invite cascade. */
  int purgeClosedRooms(Duration retention) {
    return jdbcClient
        .sql(
            "delete from room where state = 'CLOSED'"
                + " and closed_at <= now() - make_interval(secs => :seconds)")
        .param("seconds", (int) retention.toSeconds())
        .update();
  }
}
