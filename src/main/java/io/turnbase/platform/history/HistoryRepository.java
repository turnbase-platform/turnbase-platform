package io.turnbase.platform.history;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code data-model-history.md} §1–§3: the recording writes and the profile tab's read. */
@Repository
class HistoryRepository {

  private static final TypeReference<Map<String, Object>> STATS = new TypeReference<>() {};

  private final JdbcClient jdbcClient;
  private final ObjectMapper objectMapper;

  HistoryRepository(JdbcClient jdbcClient, ObjectMapper objectMapper) {
    this.jdbcClient = jdbcClient;
    this.objectMapper = objectMapper;
  }

  /** {@code true} only when this call inserted the row — the idempotency key of §1. */
  boolean insertCompletion(
      UUID sessionId,
      UUID gameId,
      String gameVersion,
      Instant completedAt,
      int playerCount,
      int teamCount,
      boolean draw) {
    return jdbcClient
            .sql(
                """
                insert into game_session_completion
                  (session_id, game_id, game_version, completed_at, player_count, team_count, draw)
                values (:s, :g, :v, :at, :players, :teams, :draw)
                on conflict (session_id) do nothing
                """)
            .param("s", sessionId)
            .param("g", gameId)
            .param("v", gameVersion)
            .param("at", Timestamp.from(completedAt))
            .param("players", playerCount)
            .param("teams", teamCount)
            .param("draw", draw)
            .update()
        > 0;
  }

  /** The counter's only writer (§3). Zero rows when the game row is gone: under-count only. */
  void incrementCompletedSessions(UUID gameId) {
    jdbcClient
        .sql(
            "update game set completed_session_count = completed_session_count + 1"
                + " where game_id = :g")
        .param("g", gameId)
        .update();
  }

  Optional<String> titleOf(UUID gameId) {
    return jdbcClient
        .sql("select title from game where game_id = :g")
        .param("g", gameId)
        .query(String.class)
        .optional();
  }

  /** FR-HIST-003: guests (and anything not a registered account) accrue no history. */
  List<UUID> registeredAmong(Collection<UUID> userIds) {
    if (userIds.isEmpty()) {
      return List.of();
    }
    return jdbcClient
        .sql("select user_id from app_user where user_id in (:ids) and kind = 'REGISTERED'")
        .param("ids", userIds)
        .query(UUID.class)
        .list();
  }

  void insertEntry(
      UUID sessionId,
      UUID userId,
      UUID gameId,
      String gameName,
      Instant completedAt,
      int placement,
      int teamCount,
      boolean draw,
      Map<String, String> playerStats,
      Map<String, String> teamStats) {
    jdbcClient
        .sql(
            """
            insert into game_history_entry
              (session_id, user_id, game_id, game_name_snapshot, completed_at, placement,
               team_count, draw, player_stats, team_stats)
            values (:s, :u, :g, :name, :at, :placement, :teams, :draw,
                    :playerStats::jsonb, :teamStats::jsonb)
            on conflict (session_id, user_id) do nothing
            """)
        .param("s", sessionId)
        .param("u", userId)
        .param("g", gameId)
        .param("name", gameName)
        .param("at", Timestamp.from(completedAt))
        .param("placement", placement)
        .param("teams", teamCount)
        .param("draw", draw)
        .param("playerStats", json(playerStats))
        .param("teamStats", json(teamStats))
        .update();
  }

  Optional<ProfileVisibility> findProfile(String usernameSkeleton) {
    return jdbcClient
        .sql(
            """
            select u.user_id, u.kind, u.deleted_at, p.profile_private, p.history_visibility
            from app_user u
            left join user_privacy_settings p on p.user_id = u.user_id
            where u.username_skeleton = :skeleton
            """)
        .param("skeleton", usernameSkeleton)
        .query(
            (rs, rowNum) ->
                new ProfileVisibility(
                    rs.getObject("user_id", UUID.class),
                    rs.getString("kind"),
                    rs.getObject("deleted_at", Timestamp.class) != null,
                    rs.getObject("profile_private", Boolean.class),
                    rs.getString("history_visibility")))
        .optional();
  }

  /** {@code ghe_user_recent_idx} order; the name filter is {@code ghe_user_name_trgm_idx}. */
  List<Row> page(UUID userId, @Nullable String search, int limit, long offset) {
    return jdbcClient
        .sql(
            """
            select e.game_id, e.game_name_snapshot, e.completed_at, e.placement, e.team_count,
                   e.draw, e.player_stats::text as player_stats, e.team_stats::text as team_stats,
                   exists (select 1 from game g
                           where g.game_id = e.game_id and g.deleted_at is null) as linkable
            from game_history_entry e
            where e.user_id = :u
              and (:pattern::text is null
                   or lower(e.game_name_snapshot) like :pattern::text escape '\\')
            order by e.completed_at desc, e.entry_id desc
            limit :limit offset :offset
            """)
        .param("u", userId)
        .param("pattern", pattern(search))
        .param("limit", limit)
        .param("offset", offset)
        .query(
            (rs, rowNum) ->
                new Row(
                    rs.getObject("game_id", UUID.class),
                    rs.getString("game_name_snapshot"),
                    rs.getTimestamp("completed_at").toInstant(),
                    rs.getInt("placement"),
                    rs.getInt("team_count"),
                    rs.getBoolean("draw"),
                    parse(rs.getString("player_stats")),
                    parse(rs.getString("team_stats")),
                    rs.getBoolean("linkable")))
        .list();
  }

  long count(UUID userId, @Nullable String search) {
    Long total =
        jdbcClient
            .sql(
                """
                select count(*) from game_history_entry e
                where e.user_id = :u
                  and (:pattern::text is null
                       or lower(e.game_name_snapshot) like :pattern::text escape '\\')
                """)
            .param("u", userId)
            .param("pattern", pattern(search))
            .query(Long.class)
            .single();
    return total == null ? 0 : total;
  }

  private static @Nullable String pattern(@Nullable String search) {
    if (search == null || search.isBlank()) {
      return null;
    }
    String escaped =
        search.strip().toLowerCase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    return "%" + escaped + "%";
  }

  private String json(Map<String, String> map) {
    try {
      return objectMapper.writeValueAsString(map);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("stats map is not serializable", e);
    }
  }

  private Map<String, Object> parse(String json) {
    try {
      return objectMapper.readValue(json, STATS);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("stored stats are not a JSON object", e);
    }
  }

  record ProfileVisibility(
      UUID userId,
      String kind,
      boolean deleted,
      @Nullable Boolean profilePrivate,
      @Nullable String historyVisibility) {}

  record Row(
      UUID gameId,
      String gameName,
      Instant completedAt,
      int placement,
      int teamCount,
      boolean draw,
      Map<String, Object> playerStats,
      Map<String, Object> teamStats,
      boolean linkable) {}
}
