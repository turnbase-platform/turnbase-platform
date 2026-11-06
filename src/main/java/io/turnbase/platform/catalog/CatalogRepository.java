package io.turnbase.platform.catalog;

import io.turnbase.platform.common.LikePattern;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Direct SQL against {@code game}, {@code game_version}, {@code game_tag}, {@code app_user} and
 * (for the active player count, FR-GDET-005) {@code room}/{@code room_participant} — {@code
 * catalog-rest-api.md}, {@code data-model-catalog.md} §1–3, {@code data-model.md} §1–2. Follows
 * {@code admin.AdminGameRepository}'s established precedent of direct-SQL-against-another-
 * feature's-table rather than a cross-module API that does not exist yet.
 */
@Repository
class CatalogRepository {

  private final JdbcClient jdbcClient;

  CatalogRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  private static final String CATALOG_COLUMNS =
      """
      g.game_id, g.title, coalesce(u.username, 'TurnBase') as author_username,
      g.short_description, gv.min_players, gv.max_players, gv.supports_ai,
      gv.supports_spectators, gv.version, g.badge, g.created_at, g.updated_at
      """;

  private static final String CATALOG_FROM =
      """
      from game g
      join game_version gv on gv.version_id = g.active_version_id
      left join app_user u on u.user_id = g.owner_user_id
      """;

  private static final String LIST_SELECT = "select " + CATALOG_COLUMNS + CATALOG_FROM;

  private static final String DETAIL_SELECT =
      "select "
          + CATALOG_COLUMNS
          + ", g.info_doc_markdown, g.completed_session_count,"
          + " (gv.manifest -> 'credits')::text as credits_json "
          + CATALOG_FROM;

  /** {@code catalog-rest-api.md} §3. */
  List<CatalogRowResponse> listGames(@Nullable UUID viewerId, CatalogListFilter filter) {
    WhereClause where = buildListWhereClause(viewerId, filter);
    String sql =
        LIST_SELECT
            + " where "
            + where.sql()
            + " order by "
            + orderByClause(filter.sort())
            + " limit :limit offset :offset";
    var spec = jdbcClient.sql(sql);
    for (var entry : where.params().entrySet()) {
      spec = spec.param(entry.getKey(), entry.getValue());
    }
    spec = spec.param("limit", filter.pageSize()).param("offset", filter.offset());
    return spec.query(CatalogRepository::mapRow).list();
  }

  /** {@code catalog-rest-api.md} §3. */
  long countGames(@Nullable UUID viewerId, CatalogListFilter filter) {
    WhereClause where = buildListWhereClause(viewerId, filter);
    String sql =
        "select count(*) from game g join game_version gv on gv.version_id = g.active_version_id"
            + " left join app_user u on u.user_id = g.owner_user_id where "
            + where.sql();
    var spec = jdbcClient.sql(sql);
    for (var entry : where.params().entrySet()) {
      spec = spec.param(entry.getKey(), entry.getValue());
    }
    Long count = spec.query(Long.class).single();
    return count == null ? 0 : count;
  }

  /**
   * {@code catalog-rest-api.md} §4 — the {@code 410}-vs-{@code 404} split. {@code empty} means no
   * row named {@code gameId} at all, which the caller treats identically to "not visible" (both a
   * {@code 404}).
   */
  Optional<Boolean> isSoftDeleted(UUID gameId) {
    return jdbcClient
        .sql("select deleted_at is not null as deleted from game where game_id = :gameId")
        .param("gameId", gameId)
        .query((rs, rowNum) -> rs.getBoolean("deleted"))
        .optional();
  }

  /**
   * {@code catalog-rest-api.md} §2/§4 — the visibility predicate, owner override included. {@code
   * empty} is the caller's {@code 404}; this method is never called once {@link #isSoftDeleted} has
   * already returned {@code true} for the same id.
   */
  Optional<GameDetailRow> findVisibleDetail(UUID gameId, @Nullable UUID viewerId) {
    WhereClause visibility = buildVisibilityClause(viewerId);
    String sql = DETAIL_SELECT + " where g.game_id = :gameId and " + visibility.sql();
    var spec = jdbcClient.sql(sql).param("gameId", gameId);
    for (var entry : visibility.params().entrySet()) {
      spec = spec.param(entry.getKey(), entry.getValue());
    }
    return spec.query(CatalogRepository::mapDetailRow).optional();
  }

  /** {@code catalog-rest-api.md} §4 — the active version's tag projection, alphabetical. */
  List<String> findTags(UUID gameId) {
    return jdbcClient
        .sql("select tag from game_tag where game_id = :gameId order by tag")
        .param("gameId", gameId)
        .query(String.class)
        .list();
  }

  /**
   * {@code catalog-rest-api.md} §4, FR-GDET-005 — {@code data-model.md} §5's documented purpose of
   * {@code room_open_game_idx}: how many players are currently in rooms for this game. A
   * participant in grace ({@code left_at IS NULL}) still counts — they still hold a slot.
   */
  long countActivePlayers(UUID gameId) {
    Long count =
        jdbcClient
            .sql(
                """
                select count(*)
                  from room r
                  join room_participant rp on rp.room_id = r.room_id
                 where r.game_id = :gameId and r.state <> 'CLOSED'
                   and rp.role = 'PLAYER' and rp.left_at is null
                """)
            .param("gameId", gameId)
            .query(Long.class)
            .single();
    return count == null ? 0 : count;
  }

  private WhereClause buildListWhereClause(@Nullable UUID viewerId, CatalogListFilter filter) {
    WhereClause visibility = buildVisibilityClause(viewerId);
    Map<String, Object> params = new LinkedHashMap<>(visibility.params());
    StringBuilder sql = new StringBuilder(visibility.sql());

    if (filter.search() != null && !filter.search().isBlank()) {
      sql.append(
          """
           and ( lower(g.title) like :search escape '\\'
              or lower(coalesce(u.username, 'turnbase')) like :search escape '\\'
              or exists (select 1 from game_tag gt where gt.game_id = g.game_id
                         and lower(gt.tag) like :search escape '\\') )
          """);
      params.put("search", LikePattern.containsLowerCase(filter.search()));
    }
    if (filter.minPlayers() != null) {
      sql.append(" and gv.max_players >= :minPlayers\n");
      params.put("minPlayers", filter.minPlayers());
    }
    if (filter.maxPlayers() != null) {
      sql.append(" and gv.min_players <= :maxPlayers\n");
      params.put("maxPlayers", filter.maxPlayers());
    }
    if (filter.tags() != null && !filter.tags().isEmpty()) {
      sql.append(
          " and exists (select 1 from game_tag gt2 where gt2.game_id = g.game_id and gt2.tag in"
              + " (:tags))\n");
      params.put("tags", filter.tags());
    }
    if (filter.owner() != null) {
      sql.append(" and u.username = :owner\n");
      params.put("owner", filter.owner());
    }
    return new WhereClause(sql.toString(), params);
  }

  /**
   * {@code catalog-rest-api.md} §2's full predicate. {@code viewerId == null} (no session, or a
   * session belonging to nobody who could own this game) omits the owner carve-out entirely rather
   * than binding a {@code NULL} parameter against {@code owner_user_id} — a {@code NULL} bind would
   * never match by SQL's own three-valued logic, but omitting the clause states that directly
   * instead of relying on it.
   */
  private WhereClause buildVisibilityClause(@Nullable UUID viewerId) {
    Map<String, Object> params = new LinkedHashMap<>();
    String sql;
    if (viewerId == null) {
      sql =
          "g.deleted_at is null and g.archived_at is null and g.active_version_id is not null"
              + " and g.listed and g.badge <> 'UNVERIFIED'\n";
    } else {
      sql =
          "g.deleted_at is null and g.archived_at is null and g.active_version_id is not null"
              + " and (g.listed or g.owner_user_id = :viewerId)"
              + " and (g.badge <> 'UNVERIFIED' or g.owner_user_id = :viewerId)\n";
      params.put("viewerId", viewerId);
    }
    return new WhereClause(sql, params);
  }

  private static final Map<String, String> SORT_COLUMNS =
      Map.of(
          "title", "g.title",
          "author", "coalesce(u.username, '')",
          "minPlayers", "gv.min_players",
          "maxPlayers", "gv.max_players",
          "version", "(gv.version_major, gv.version_minor, gv.version_patch)",
          "createdAt", "g.created_at",
          "updatedAt", "g.updated_at");

  /**
   * {@code pagination-filtering-sorting.md} § Offset pagination: a total ordering, sort column plus
   * primary key, so identical sort values never drift non-deterministically between requests.
   */
  private static String orderByClause(Sort sort) {
    if (sort.isUnsorted()) {
      return "g.created_at desc, g.game_id";
    }
    List<String> clauses = new java.util.ArrayList<>();
    for (Sort.Order order : sort) {
      String column = SORT_COLUMNS.get(order.getProperty());
      if (column == null) {
        throw new IllegalStateException(
            "Unvalidated sort field reached the repository: " + order.getProperty());
      }
      clauses.add(column + (order.isDescending() ? " desc" : " asc"));
    }
    clauses.add("g.game_id");
    return String.join(", ", clauses);
  }

  private static CatalogRowResponse mapRow(java.sql.ResultSet rs, int rowNum)
      throws java.sql.SQLException {
    return new CatalogRowResponse(
        rs.getObject("game_id", UUID.class),
        rs.getString("title"),
        rs.getString("author_username"),
        rs.getString("short_description"),
        rs.getInt("min_players"),
        rs.getInt("max_players"),
        rs.getBoolean("supports_ai"),
        rs.getBoolean("supports_spectators"),
        rs.getString("version"),
        rs.getString("badge"),
        requireInstant(rs.getTimestamp("created_at")),
        requireInstant(rs.getTimestamp("updated_at")));
  }

  private static GameDetailRow mapDetailRow(java.sql.ResultSet rs, int rowNum)
      throws java.sql.SQLException {
    return new GameDetailRow(
        rs.getObject("game_id", UUID.class),
        rs.getString("title"),
        rs.getString("author_username"),
        rs.getString("short_description"),
        rs.getString("info_doc_markdown"),
        rs.getString("credits_json"),
        rs.getLong("completed_session_count"),
        rs.getInt("min_players"),
        rs.getInt("max_players"),
        rs.getBoolean("supports_ai"),
        rs.getBoolean("supports_spectators"),
        rs.getString("version"),
        rs.getString("badge"),
        requireInstant(rs.getTimestamp("created_at")),
        requireInstant(rs.getTimestamp("updated_at")));
  }

  private static Instant requireInstant(Timestamp timestamp) {
    return timestamp.toInstant();
  }

  private record WhereClause(String sql, Map<String, Object> params) {}

  record GameDetailRow(
      UUID gameId,
      String title,
      String authorUsername,
      String shortDescription,
      @Nullable String infoDocMarkdown,
      @Nullable String creditsJson,
      long completedSessionCount,
      int minPlayers,
      int maxPlayers,
      boolean supportsAi,
      boolean supportsSpectators,
      String version,
      String badge,
      Instant createdAt,
      Instant updatedAt) {}
}
