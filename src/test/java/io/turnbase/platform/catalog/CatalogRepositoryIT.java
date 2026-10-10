package io.turnbase.platform.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import io.turnbase.platform.PostgresIntegrationTest;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * {@code catalog-rest-api.md} §2's visibility predicate and §4's active player count, exercised
 * directly against the repository — proves IMP-M4-01's first and second Done-when clauses (an
 * Unverified game is invisible to everyone but its owner; a game carries exactly one badge from one
 * column) at the SQL layer, following {@code GameVersionRepositoryIT}'s lighter Flyway-migrate
 * pattern.
 */
class CatalogRepositoryIT extends PostgresIntegrationTest {

  private static JdbcClient jdbcClient;
  private static CatalogRepository repository;

  @BeforeAll
  static void setUpSchemaAndRepository() {
    Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .load()
        .migrate();
    DriverManagerDataSource dataSource =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    jdbcClient = JdbcClient.create(dataSource);
    repository = new CatalogRepository(jdbcClient);
  }

  @BeforeEach
  void cleanTables() {
    jdbcClient
        .sql("truncate room_participant, room, game_tag, game_version, game, app_user cascade")
        .update();
  }

  @Test
  void unverifiedGameIsInvisibleToEveryoneButItsOwner() {
    UUID ownerId = insertUser();
    UUID gameId = activeGame(ownerId, "UNVERIFIED", true, "Community Game");

    assertThat(repository.findVisibleDetail(gameId, null)).isEmpty();
    assertThat(repository.findVisibleDetail(gameId, UUID.randomUUID())).isEmpty();
    assertThat(repository.findVisibleDetail(gameId, ownerId)).isPresent();

    assertThat(listTitles(null)).doesNotContain("Community Game");
    assertThat(listTitles(UUID.randomUUID())).doesNotContain("Community Game");
    assertThat(listTitles(ownerId)).contains("Community Game");
  }

  @Test
  void unlistedGameIsInvisibleToEveryoneButItsOwner() {
    UUID ownerId = insertUser();
    UUID gameId = activeGame(ownerId, "VERIFIED", false, "Hidden Game");

    assertThat(repository.findVisibleDetail(gameId, null)).isEmpty();
    assertThat(repository.findVisibleDetail(gameId, ownerId)).isPresent();
  }

  @Test
  void softDeletedGameIsInvisibleEvenToItsOwner() {
    UUID ownerId = insertUser();
    UUID gameId = activeGame(ownerId, "VERIFIED", true, "Doomed Game");
    jdbcClient
        .sql("update game set deleted_at = now() where game_id = :id")
        .param("id", gameId)
        .update();

    assertThat(repository.findVisibleDetail(gameId, ownerId)).isEmpty();
    assertThat(repository.isSoftDeleted(gameId)).contains(true);
  }

  @Test
  void gameWithNoActiveVersionIsInvisible() {
    UUID gameId = UUID.randomUUID();
    jdbcClient
        .sql(
            "insert into game (game_id, owner_user_id, title, short_description, badge, listed)"
                + " values (:id, null, 'No Version', 'desc', 'OFFICIAL', true)")
        .param("id", gameId)
        .update();

    assertThat(repository.findVisibleDetail(gameId, null)).isEmpty();
  }

  @Test
  void nullOwnerRendersTurnBaseAsTheAuthor() {
    UUID gameId = activeGame(null, "OFFICIAL", true, "Chess");

    CatalogRepository.GameDetailRow row = repository.findVisibleDetail(gameId, null).orElseThrow();

    assertThat(row.authorUsername()).isEqualTo("TurnBase");
    assertThat(row.badge()).isEqualTo("OFFICIAL");
  }

  @Test
  void activePlayerCountCountsOnlyPlayersInOpenRoomsForThisGameAndVersion() {
    UUID gameId = activeGame(null, "OFFICIAL", true, "Chess");
    UUID activePlayer = insertUser();
    UUID gracePlayer = insertUser();
    UUID leftPlayer = insertUser();
    UUID spectator = insertUser();
    UUID closedRoomPlayer = insertUser();

    UUID openRoom = insertRoom(gameId, "1.0.0", "LOBBY");
    insertParticipant(openRoom, activePlayer, "PLAYER", 1, false);
    insertParticipant(openRoom, gracePlayer, "PLAYER", 2, false);
    insertParticipant(openRoom, leftPlayer, "PLAYER", 3, true);
    insertParticipant(openRoom, spectator, "SPECTATOR", 4, false);

    UUID closedRoom = insertRoom(gameId, "1.0.0", "CLOSED");
    insertParticipant(closedRoom, closedRoomPlayer, "PLAYER", 1, false);

    assertThat(repository.countActivePlayers(gameId)).isEqualTo(2);
  }

  @Test
  void sortByVersionOrdersByTheThreeIntegerComponentsNotLexicographically() {
    UUID gameA = activeGame(null, "OFFICIAL", true, "Alpha", "2.0.0");
    UUID gameB = activeGame(null, "OFFICIAL", true, "Beta", "10.0.0");

    CatalogListFilter filter =
        new CatalogListFilter(
            null, null, null, null, null, Sort.by(Sort.Order.asc("version")), 25, 0);
    List<CatalogRowResponse> rows = repository.listGames(null, filter);

    assertThat(rows).extracting(CatalogRowResponse::gameId).containsExactly(gameA, gameB);
  }

  @Test
  void searchTreatsPercentAndUnderscoreAsLiterals() {
    UUID owner = insertUser();
    activeGame(owner, "VERIFIED", true, "Plain Game");
    activeGame(owner, "VERIFIED", true, "100% Fun");
    activeGame(owner, "VERIFIED", true, "snake_case");
    activeGame(owner, "VERIFIED", true, "snakeXcase");

    assertThat(searchTitles("%")).containsExactly("100% Fun");
    assertThat(searchTitles("_")).containsExactly("snake_case");
    assertThat(searchTitles("e_c")).containsExactly("snake_case");
    assertThat(searchTitles("\\")).isEmpty();
  }

  private List<String> searchTitles(String search) {
    CatalogListFilter filter =
        new CatalogListFilter(search, null, null, null, null, Sort.unsorted(), 25, 0);
    return repository.listGames(null, filter).stream().map(CatalogRowResponse::title).toList();
  }

  private List<String> listTitles(@Nullable UUID viewerId) {
    CatalogListFilter filter =
        new CatalogListFilter(null, null, null, null, null, Sort.unsorted(), 25, 0);
    return repository.listGames(viewerId, filter).stream().map(CatalogRowResponse::title).toList();
  }

  private UUID activeGame(@Nullable UUID ownerId, String badge, boolean listed, String title) {
    return activeGame(ownerId, badge, listed, title, "1.0.0");
  }

  private UUID activeGame(
      @Nullable UUID ownerId, String badge, boolean listed, String title, String version) {
    UUID gameId = UUID.randomUUID();
    jdbcClient
        .sql(
            "insert into game (game_id, owner_user_id, title, short_description, badge, listed)"
                + " values (:id, :owner, :title, 'A short description.', :badge, :listed)")
        .param("id", gameId)
        .param("owner", ownerId)
        .param("title", title)
        .param("badge", badge)
        .param("listed", listed)
        .update();
    UUID versionId = UUID.randomUUID();
    jdbcClient
        .sql(
            """
            insert into game_version (version_id, game_id, version, status, declared_size_bytes,
              upload_expires_at, min_players, max_players, supports_ai, supports_spectators,
              supports_mid_game_join, activated_at)
            values (:id, :gameId, :version, 'ACTIVE', 1024, now(), 2, 4, true, true, false, now())
            """)
        .param("id", versionId)
        .param("gameId", gameId)
        .param("version", version)
        .update();
    jdbcClient
        .sql("update game set active_version_id = :versionId where game_id = :gameId")
        .param("versionId", versionId)
        .param("gameId", gameId)
        .update();
    return gameId;
  }

  private UUID insertUser() {
    UUID userId = UUID.randomUUID();
    jdbcClient
        .sql(
            "insert into app_user (user_id, kind, username, username_skeleton, email, email_source)"
                + " values (:id, 'REGISTERED', :username, :username, :email, 'SELF')")
        .param("id", userId)
        .param("username", "dev" + userId.toString().replace("-", "").substring(0, 10))
        .param("email", "dev-" + userId + "@example.com")
        .update();
    return userId;
  }

  private UUID insertRoom(UUID gameId, String gameVersion, String state) {
    UUID roomId = UUID.randomUUID();
    jdbcClient
        .sql(
            "insert into room (room_id, game_id, game_version, visibility, state)"
                + " values (:id, :gameId, :gameVersion, 'PUBLIC', :state)")
        .param("id", roomId)
        .param("gameId", gameId)
        .param("gameVersion", gameVersion)
        .param("state", state)
        .update();
    return roomId;
  }

  private void insertParticipant(
      UUID roomId, UUID userId, String role, int joinOrder, boolean left) {
    jdbcClient
        .sql(
            "insert into room_participant (room_id, user_id, role, join_order, left_at)"
                + " values (:roomId, :userId, :role, :joinOrder, :leftAt)")
        .param("roomId", roomId)
        .param("userId", userId)
        .param("role", role)
        .param("joinOrder", joinOrder)
        .param("leftAt", left ? java.sql.Timestamp.from(java.time.Instant.now()) : null)
        .update();
  }
}
