package io.turnbase.platform.room;

import io.turnbase.platform.common.LikePattern;
import io.turnbase.platform.room.RoomRecords.CreatableGame;
import io.turnbase.platform.room.RoomRecords.CreatableGameCandidate;
import io.turnbase.platform.room.RoomRecords.ParticipantRow;
import io.turnbase.platform.room.RoomRecords.RoomForJoin;
import io.turnbase.platform.room.RoomRecords.RoomListRow;
import io.turnbase.platform.room.RoomRecords.RoomResourceRow;
import io.turnbase.platform.room.RoomRecords.RoomSnapshotHeader;
import io.turnbase.platform.room.RoomRecords.RoomStateSnapshotWithVersion;
import io.turnbase.platform.room.RoomRecords.TeamTemplateInfo;
import io.turnbase.platform.room.RoomRecords.WhereClause;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Room reads: the ROOM_STATE snapshot, the REST resources and the list. Split from the former
 * {@code RoomRepository} by {@code IMP-M20-14}; SQL and transactions are as they were.
 */
@Repository
class RoomReadRepository {

  private final JdbcClient jdbcClient;
  private final JsonMapper objectMapper;

  RoomReadRepository(JdbcClient jdbcClient, JsonMapper objectMapper) {
    this.jdbcClient = jdbcClient;
    this.objectMapper = objectMapper;
  }

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

  Map<String, Object> readParameters(@Nullable String parametersJson) {
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
      left join lateral (select count(*) as player_count from room_participant
                         where room_id = r.room_id and role = 'PLAYER' and left_at is null) pc
        on true
      left join lateral (select sum(configured_size) as player_capacity from room_team
                         where room_id = r.room_id) cap
        on true
      left join lateral (select count(*) as spectator_count from room_participant
                         where room_id = r.room_id and role = 'SPECTATOR' and left_at is null) sc
        on true
      """;

  /** Post-mutation refetch: the caller is already known to have access (they just acted on it). */
  Optional<RoomResourceRow> findRoomResource(UUID roomId) {
    return jdbcClient
        .sql(RESOURCE_SELECT + " where r.room_id = :roomId")
        .param("roomId", roomId)
        .query(RoomReadRepository::mapResourceRow)
        .optional();
  }

  /**
   * {@code room-rest-api.md} §4: {@code reachable()} from §3 ({@code visible()}, a current
   * participant, or a pending invitation). The participant carve-out is — necessary so a client can
   * re-fetch (page refresh, pre-socket read) a room it already belongs to, including an {@code
   * INVITE_ONLY} one, without re-presenting the invite code. §4's own text only rules out an
   * id-based bypass of the invite code, not a membership-based one; §3's predicate is written for
   * the *browsing* case where the caller is not yet a member. Not applied to listing (§3 below),
   * where FR-LIST-007's "for any player" stays literal.
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
                                   and rp.left_at is null)
                      or exists (select 1 from room_invitation ri
                                 where ri.room_id = r.room_id
                                   and ri.invitee_user_id = :viewerId) )
                """)
        .param("roomId", roomId)
        .param("viewerId", viewerId)
        .query(RoomReadRepository::mapResourceRow)
        .optional();
  }

  /** {@code room-rest-api.md} §3.1: {@code visible()} bypassed, {@code state <> 'CLOSED'} kept. */
  Optional<RoomResourceRow> resolveInviteCode(String inviteCode) {
    return jdbcClient
        .sql(RESOURCE_SELECT + " where r.invite_code = :inviteCode and r.state <> 'CLOSED'")
        .param("inviteCode", inviteCode)
        .query(RoomReadRepository::mapResourceRow)
        .optional();
  }

  /**
   * {@code room-rest-api.md} §3's {@code reservedSlot}: the caller's live grace reservation
   * (FR-GRACE-005), whichever room it is in. Independent of the list's filters and page. Due but
   * unswept reservations ({@code grace_expires_at <= now()}) are not live, and the remaining
   * seconds are clamped to at least 1 as for {@code disconnectedPlayers}.
   */
  Optional<RoomListResponse.ReservedSlot> findReservedSlot(UUID userId) {
    return jdbcClient
        .sql(
            """
            select rp.room_id, g.title as game_name,
                   greatest(1, ceil(extract(epoch from rp.grace_expires_at - now())))::bigint
                     as grace_remaining_seconds
            from room_participant rp
            join room r on r.room_id = rp.room_id
            join game g on g.game_id = r.game_id
            where rp.user_id = :userId and rp.left_at is null
              and rp.grace_expires_at > now() and r.state = 'IN_PROGRESS'
            order by rp.grace_expires_at desc
            limit 1
            """)
        .param("userId", userId)
        .query(
            (rs, rowNum) ->
                new RoomListResponse.ReservedSlot(
                    rs.getObject("room_id", UUID.class),
                    rs.getString("game_name"),
                    rs.getLong("grace_remaining_seconds")))
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
        left join lateral (select count(*) as player_count from room_participant
                           where room_id = r.room_id and role = 'PLAYER' and left_at is null) pc
          on true
        left join lateral (select sum(configured_size) as player_capacity from room_team
                           where room_id = r.room_id) cap
          on true
        left join lateral (select count(*) as spectator_count from room_participant
                           where room_id = r.room_id and role = 'SPECTATOR' and left_at is null) sc
          on true
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
    return spec.query(RoomReadRepository::mapListRow).list();
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
        left join lateral (select count(*) as player_count from room_participant
                           where room_id = r.room_id and role = 'PLAYER' and left_at is null) pc
          on true
        left join lateral (select sum(configured_size) as player_capacity from room_team
                           where room_id = r.room_id) cap
          on true
        left join lateral (select count(*) as spectator_count from room_participant
                           where room_id = r.room_id and role = 'SPECTATOR' and left_at is null) sc
          on true
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
      sql.append(
          " and (lower(g.title) like :search escape '\\'"
              + " or lower(hu.username) like :search escape '\\')\n");
      params.put("search", LikePattern.containsLowerCase(filter.search()));
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

  boolean isVisible(String visibility, @Nullable UUID hostUserId, UUID viewerId) {
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

  List<TeamTemplateInfo> parseTeamTemplates(@Nullable String manifestJson) {
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

  static CreatableGameCandidate mapCreatableGameCandidate(ResultSet rs, int rowNum)
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

  static RoomForJoin mapRoomForJoin(ResultSet rs, int rowNum) throws SQLException {
    return new RoomForJoin(
        rs.getObject("room_id", UUID.class),
        rs.getString("visibility"),
        rs.getString("state"),
        rs.getBoolean("spectating_enabled"),
        rs.getObject("host_user_id", UUID.class),
        rs.getString("invite_code"),
        rs.getBoolean("supports_spectators"),
        rs.getBoolean("supports_mid_game_join"),
        Objects.requireNonNull(rs.getString("caller_username"), "no such caller"));
  }

  static RoomResourceRow mapResourceRow(ResultSet rs, int rowNum) throws SQLException {
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

  static RoomListRow mapListRow(ResultSet rs, int rowNum) throws SQLException {
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

  // ---------------------------------------------------------------------

  String usernameOf(UUID userId) {
    return jdbcClient
        .sql("select username from app_user where user_id = :userId")
        .param("userId", userId)
        .query(String.class)
        .single();
  }
}
