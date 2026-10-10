package io.turnbase.platform.upload;

import static org.assertj.core.api.Assertions.assertThat;

import io.turnbase.platform.PostgresIntegrationTest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code bundle-format.md} §5.4's activation transaction, exercised directly against the repository
 * rather than through the full HTTP pipeline. Two of IMP-M4-03's Done-when clauses can only be
 * proven this way: a {@code VERIFIED}/owned game can never pass stage E4 (jvm is official-only,
 * ADR-0006), so the {@code VERIFIED -> UNVERIFIED} transition and the two-slot backward-ordering
 * guard are untestable end-to-end and are proven here instead, against the same transaction the
 * real pipeline calls.
 */
class GameVersionRepositoryIT extends PostgresIntegrationTest {

  private static JdbcClient jdbcClient;
  private static GameVersionRepository repository;

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
    repository = new GameVersionRepository(jdbcClient, JsonMapper.builder().build());
  }

  @BeforeEach
  void cleanTables() {
    jdbcClient.sql("truncate game_tag, game_version, game, app_user cascade").update();
  }

  @Test
  void firstActivationOfAnOfficialGameSeedsGameFieldsAndKeepsOfficialBadge() {
    UUID gameId = insertGame(null, "OFFICIAL", "Placeholder", "placeholder desc");
    UUID versionId = insertPendingVersion(gameId, "1.0.0");

    var outcome =
        repository.activate(
            activationRequest(gameId, versionId, "1.0.0", "Chess", "The classic game", "# Rules"));

    assertThat(outcome).isPresent();
    assertThat(outcome.get().firstActivation()).isTrue();

    GameRow game = repository.findGame(gameId).orElseThrow();
    assertThat(game.badge()).isEqualTo("OFFICIAL");
    assertThat(game.activeVersionId()).isEqualTo(versionId);

    String title =
        jdbcClient
            .sql("select title from game where game_id = :id")
            .param("id", gameId)
            .query(String.class)
            .single();
    String shortDescription =
        jdbcClient
            .sql("select short_description from game where game_id = :id")
            .param("id", gameId)
            .query(String.class)
            .single();
    assertThat(title).isEqualTo("Chess");
    assertThat(shortDescription).isEqualTo("The classic game");

    GameVersionRow versionRow = repository.findByVersionId(versionId).orElseThrow();
    assertThat(versionRow.status()).isEqualTo(GameVersionStatus.ACTIVE);

    Integer minPlayers =
        jdbcClient
            .sql("select min_players from game_version where version_id = :id")
            .param("id", versionId)
            .query(Integer.class)
            .single();
    Integer maxPlayers =
        jdbcClient
            .sql("select max_players from game_version where version_id = :id")
            .param("id", versionId)
            .query(Integer.class)
            .single();
    assertThat(minPlayers).isEqualTo(2);
    assertThat(maxPlayers).isEqualTo(2);

    Long tagCount =
        jdbcClient
            .sql("select count(*) from game_tag where game_id = :id and tag = 'strategy'")
            .param("id", gameId)
            .query(Long.class)
            .single();
    assertThat(tagCount).isEqualTo(1);
  }

  @Test
  void secondActivationOfAnOfficialGameLeavesTheBadgeOfficial() {
    UUID gameId = insertGame(null, "OFFICIAL", "Chess", "The classic game");
    UUID v1 = insertPendingVersion(gameId, "1.0.0");
    repository.activate(activationRequest(gameId, v1, "1.0.0", "Chess", "The classic game", null));
    UUID v2 = insertPendingVersion(gameId, "1.1.0");

    var outcome =
        repository.activate(
            activationRequest(gameId, v2, "1.1.0", "Chess", "The classic game", null));

    assertThat(outcome).isPresent();
    assertThat(outcome.get().firstActivation()).isFalse();
    assertThat(repository.findGame(gameId).orElseThrow().badge()).isEqualTo("OFFICIAL");
    assertThat(repository.findByVersionId(v1).orElseThrow().status())
        .isEqualTo(GameVersionStatus.SUPERSEDED);
    assertThat(repository.findByVersionId(v2).orElseThrow().status())
        .isEqualTo(GameVersionStatus.ACTIVE);
  }

  @Test
  void activationClearsVerifiedToUnverified() {
    UUID ownerId = insertUser();
    UUID gameId = insertGame(ownerId, "VERIFIED", "Community Game", "desc");
    UUID versionId = insertPendingVersion(gameId, "1.0.0");

    repository.activate(
        activationRequest(gameId, versionId, "1.0.0", "Community Game", "desc", null));

    assertThat(repository.findGame(gameId).orElseThrow().badge()).isEqualTo("UNVERIFIED");
  }

  @Test
  void activationLeavesAnAlreadyUnverifiedBadgeUnchanged() {
    UUID ownerId = insertUser();
    UUID gameId = insertGame(ownerId, "UNVERIFIED", "Community Game", "desc");
    UUID versionId = insertPendingVersion(gameId, "1.0.0");

    repository.activate(
        activationRequest(gameId, versionId, "1.0.0", "Community Game", "desc", null));

    assertThat(repository.findGame(gameId).orElseThrow().badge()).isEqualTo("UNVERIFIED");
  }

  @Test
  void twoSlotsCannotWalkTheActiveVersionBackwards() {
    UUID gameId = insertGame(null, "OFFICIAL", "Placeholder", "placeholder");
    UUID vBase = insertPendingVersion(gameId, "1.2.0");
    repository.activate(activationRequest(gameId, vBase, "1.2.0", "Game", "desc", null));

    UUID vHigh = insertPendingVersion(gameId, "1.3.0");
    UUID vMid = insertPendingVersion(gameId, "1.2.5");

    var highOutcome =
        repository.activate(activationRequest(gameId, vHigh, "1.3.0", "Game", "desc", null));
    var midOutcome =
        repository.activate(activationRequest(gameId, vMid, "1.2.5", "Game", "desc", null));

    assertThat(highOutcome).isPresent();
    assertThat(midOutcome)
        .isEmpty(); // the authoritative recheck under the game row lock rejects it

    GameRow game = repository.findGame(gameId).orElseThrow();
    assertThat(game.activeVersionId()).isEqualTo(vHigh);
    assertThat(repository.findByVersionId(vHigh).orElseThrow().status())
        .isEqualTo(GameVersionStatus.ACTIVE);
    // vMid's row is untouched by activate() itself on abort — the caller (BundleValidationService)
    // is what moves it to FAILED, proven at the full-stack level.
    assertThat(repository.findByVersionId(vMid).orElseThrow().status())
        .isEqualTo(GameVersionStatus.PROCESSING);
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

  private UUID insertGame(
      @Nullable UUID ownerId, String badge, String title, String shortDescription) {
    UUID gameId = UUID.randomUUID();
    jdbcClient
        .sql(
            "insert into game (game_id, owner_user_id, title, short_description, badge, listed)"
                + " values (:id, :owner, :title, :shortDescription, :badge, false)")
        .param("id", gameId)
        .param("owner", ownerId)
        .param("title", title)
        .param("shortDescription", shortDescription)
        .param("badge", badge)
        .update();
    return gameId;
  }

  private UUID insertPendingVersion(UUID gameId, String version) {
    UUID versionId = UUID.randomUUID();
    jdbcClient
        .sql(
            "insert into game_version (version_id, game_id, version, status, declared_size_bytes, upload_expires_at)"
                + " values (:id, :gameId, :version, 'PROCESSING', 1024, :expiresAt)")
        .param("id", versionId)
        .param("gameId", gameId)
        .param("version", version)
        .param("expiresAt", Timestamp.from(Instant.now().plusSeconds(3600)))
        .update();
    return versionId;
  }

  private GameVersionRepository.ActivationRequest activationRequest(
      UUID gameId,
      UUID versionId,
      String version,
      String title,
      String shortDescription,
      @Nullable String infoDoc) {
    return new GameVersionRepository.ActivationRequest(
        gameId,
        versionId,
        version,
        "{\"title\":\"" + title + "\"}",
        "0.3.0",
        2,
        2,
        false,
        false,
        false,
        List.of("strategy"),
        title,
        shortDescription,
        infoDoc);
  }
}
