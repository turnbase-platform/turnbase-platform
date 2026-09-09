package io.turnbase.platform.room;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.turnbase.platform.common.BusinessRuleException;
import io.turnbase.platform.common.NotFoundException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
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

  RoomRepository(JdbcClient jdbcClient, ObjectMapper objectMapper) {
    this.jdbcClient = jdbcClient;
    this.objectMapper = objectMapper;
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

    List<TeamTemplate> templates = parseTeamTemplates(game.manifest());
    UUID firstTeamId = null;
    for (int templateIndex = 0; templateIndex < templates.size(); templateIndex++) {
      TeamTemplate template = templates.get(templateIndex);
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
      return new JoinOutcome(roomId, releaseCallersCurrentRoom(userId, roomId), Optional.empty());
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
    jdbcClient
        .sql(
            """
            insert into room_participant (room_id, user_id, role, team_id, join_order)
            values (:roomId, :userId, :role, :teamId, :joinOrder)
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
    return new JoinOutcome(roomId, released, Optional.of(joined));
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
   * an announcement, for the deferred-tail sweep (`room-lifecycle.md` §5.1 duty 2) to pick up once
   * that mechanism exists.
   */
  private Optional<ReleasedRoomResponse> releaseCallersCurrentRoom(UUID userId, UUID newRoomId) {
    record ActiveMembership(UUID roomId, @Nullable UUID graceReservationId) {}

    Optional<ActiveMembership> active =
        jdbcClient
            .sql(
                "select room_id, grace_reservation_id from room_participant"
                    + " where user_id = :userId and left_at is null and room_id <> :newRoomId")
            .param("userId", userId)
            .param("newRoomId", newRoomId)
            .query(
                (rs, rowNum) ->
                    new ActiveMembership(
                        rs.getObject("room_id", UUID.class),
                        rs.getObject("grace_reservation_id", UUID.class)))
            .optional();
    if (active.isEmpty()) {
      return Optional.empty();
    }
    ActiveMembership membership = active.get();
    String reason = membership.graceReservationId() != null ? "EARLY_RELEASE" : "LEFT";
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
    return Optional.of(new ReleasedRoomResponse(membership.roomId(), reason));
  }

  // ---------------------------------------------------------------------
  // Leave (room-rest-api.md §6)
  // ---------------------------------------------------------------------

  /**
   * {@code FR-LIFE-004}: an immediate, confirmed departure — no grace period. Idempotent: a caller
   * with no active membership in this room updates zero rows and returns empty, distinct from a
   * real departure so {@code RoomService} knows whether a {@code PLAYER_LEFT} broadcast is owed.
   */
  @Transactional
  Optional<PlayerLeftPayload> leaveRoom(UUID userId, UUID roomId) {
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
               set left_at = now(), leave_reason = 'LEFT', lost_announced_at = null,
                   grace_reservation_id = null, grace_expires_at = null
             where room_id = :roomId and user_id = :userId and left_at is null
            """)
        .param("roomId", roomId)
        .param("userId", userId)
        .update();
    return Optional.of(new PlayerLeftPayload(userId, role.get()));
  }

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

  private List<TeamTemplate> parseTeamTemplates(@Nullable String manifestJson) {
    if (manifestJson == null) {
      throw new IllegalStateException("An ACTIVE game_version must carry a validated manifest.");
    }
    JsonNode manifest;
    try {
      manifest = objectMapper.readTree(manifestJson);
    } catch (Exception e) {
      throw new IllegalStateException("Stored manifest is not valid JSON", e);
    }
    List<TeamTemplate> templates = new ArrayList<>();
    for (JsonNode templateNode : manifest.path("teamTemplates")) {
      templates.add(
          new TeamTemplate(
              templateNode.path("count").path("min").asInt(),
              templateNode.path("size").path("max").asInt()));
    }
    return templates;
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

  private record TeamTemplate(int countMin, int sizeMax) {}

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
      Optional<PlayerJoinedPayload> playerJoined) {}

  /** {@code room-websocket.md} `PLAYER_JOINED` payload shape, verbatim field-for-field. */
  record PlayerJoinedPayload(
      UUID playerId, String username, String role, @Nullable UUID teamId, long joinOrder) {}

  /** {@code room-websocket.md} `PLAYER_LEFT` payload shape. */
  record PlayerLeftPayload(UUID playerId, String role) {}
}
