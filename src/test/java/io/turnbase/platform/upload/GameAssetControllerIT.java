package io.turnbase.platform.upload;

import static org.assertj.core.api.Assertions.assertThat;

import io.turnbase.platform.PostgresIntegrationTest;
import io.turnbase.platform.TurnBaseApplication;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.client.RestTemplate;

/**
 * {@code game-asset-serving.md} §4/§4.1/§9 invariant 1 and {@code bundle-format.md} §5.3's
 * ACTIVE-or-SUPERSEDED-and-not-purged servability predicate, end to end over real HTTP — requests
 * actually go to {@code {gameId}.games.localhost} and {@code turnbase.localhost} (both resolve to
 * loopback per RFC 6761, exactly as the browser-facing design of §3 relies on), not a mocked Host
 * header, so this exercises the real {@code Host}-based virtual routing.
 *
 * <p>Keeps its own boot: its game-origin hosts and upload limits are set at startup and differ from
 * every other class. (IMP-M20-10)
 *
 * <p>Requirements protected: {@code game-development-experience.md} FR-ASSET-001, FR-ASSET-002,
 * FR-ASSET-005, FR-ASSET-009.
 */
class GameAssetControllerIT extends PostgresIntegrationTest {

  private final RestTemplate restTemplate = new RestTemplate(new JdkClientHttpRequestFactory());

  private @Nullable ConfigurableApplicationContext context;
  private JdbcClient jdbcClient;
  private ArtifactStore artifactStore;
  private int port;

  {
    restTemplate.setErrorHandler(new NoOpResponseErrorHandler());
  }

  @BeforeEach
  void bootApplication() {
    ConfigurableApplicationContext booted =
        SpringApplication.run(
            TurnBaseApplication.class,
            "--server.port=0",
            "--management.server.port=0",
            "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
            "--spring.datasource.username=" + POSTGRES.getUsername(),
            "--spring.datasource.password=" + POSTGRES.getPassword(),
            "--turnbase.artifact-store.endpoint=" + MINIO.getS3URL(),
            "--turnbase.artifact-store.bucket=" + ARTIFACT_BUCKET,
            "--turnbase.artifact-store.access-key=" + MINIO.getUserName(),
            "--turnbase.artifact-store.secret-key=" + MINIO.getPassword(),
            "--turnbase.admin.username=admin",
            "--turnbase.admin.password=admin",
            "--spring.data.redis.url=" + redisUrl());
    context = booted;
    port = Integer.parseInt(booted.getEnvironment().getProperty("local.server.port"));
    jdbcClient = booted.getBean(JdbcClient.class);
    artifactStore = booted.getBean(ArtifactStore.class);
  }

  @AfterEach
  void stopApplication() {
    if (context != null) {
      context.close();
    }
  }

  @Test
  void servesAPublishedAssetFromTheGameOriginWithTheDocumentedHeaders() {
    UUID gameId = seedGame();
    activateVersion(gameId, "1.0.0");
    byte[] content = "console.log('hi');".getBytes(StandardCharsets.UTF_8);
    artifactStore.put(ObjectKeys.unpacked(gameId, "1.0.0", "main.js"), content, "text/javascript");

    ResponseEntity<byte[]> response =
        restTemplate.getForEntity(
            gameUrl(gameId, "/g/%s/1.0.0/main.js".formatted(gameId)), byte[].class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getHeaders().getContentType()).hasToString("text/javascript");
    assertThat(response.getHeaders().getCacheControl())
        .isEqualTo("public, max-age=31536000, immutable");
    assertThat(response.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
    assertThat(response.getHeaders().getFirst("Content-Security-Policy"))
        .contains("default-src 'none'")
        .contains("frame-ancestors");
    assertThat(response.getBody()).isEqualTo(content);
  }

  @Test
  void aSupersededVersionStillServes() {
    UUID gameId = seedGame();
    activateVersion(gameId, "1.0.0");
    activateVersion(gameId, "1.1.0");
    artifactStore.put(
        ObjectKeys.unpacked(gameId, "1.0.0", "main.js"),
        "old".getBytes(StandardCharsets.UTF_8),
        "text/javascript");

    ResponseEntity<byte[]> response =
        restTemplate.getForEntity(
            gameUrl(gameId, "/g/%s/1.0.0/main.js".formatted(gameId)), byte[].class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
  }

  @Test
  void aPurgedVersionsBundleIsGone() {
    UUID gameId = seedGame();
    activateVersion(gameId, "1.0.0");
    jdbcClient
        .sql("update game_version set bundle_purged_at = now() where game_id = :gameId")
        .param("gameId", gameId)
        .update();
    artifactStore.put(
        ObjectKeys.unpacked(gameId, "1.0.0", "main.js"),
        "x".getBytes(StandardCharsets.UTF_8),
        "text/javascript");

    ResponseEntity<byte[]> response =
        restTemplate.getForEntity(
            gameUrl(gameId, "/g/%s/1.0.0/main.js".formatted(gameId)), byte[].class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void aMissingObjectIs404DespiteAServableVersion() {
    UUID gameId = seedGame();
    activateVersion(gameId, "1.0.0");

    ResponseEntity<byte[]> response =
        restTemplate.getForEntity(
            gameUrl(gameId, "/g/%s/1.0.0/nonexistent.js".formatted(gameId)), byte[].class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void theGameOriginCannotReachThePlatformApi() {
    UUID gameId = seedGame();

    ResponseEntity<byte[]> response =
        restTemplate.getForEntity(gameUrl(gameId, "/api/games"), byte[].class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void thePlatformOriginCannotReachAGameAsset() {
    UUID gameId = seedGame();
    activateVersion(gameId, "1.0.0");
    artifactStore.put(
        ObjectKeys.unpacked(gameId, "1.0.0", "main.js"),
        "x".getBytes(StandardCharsets.UTF_8),
        "text/javascript");

    ResponseEntity<byte[]> response =
        restTemplate.getForEntity(
            platformUrl("/g/%s/1.0.0/main.js".formatted(gameId)), byte[].class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  private String gameUrl(UUID gameId, String path) {
    return "http://" + gameId + ".games.localhost:" + port + path;
  }

  private String platformUrl(String path) {
    return "http://turnbase.localhost:" + port + path;
  }

  private UUID seedGame() {
    UUID gameId = UUID.randomUUID();
    jdbcClient
        .sql(
            "insert into game (game_id, owner_user_id, title, short_description, badge, listed)"
                + " values (:id, null, 'Untitled', 'placeholder', 'OFFICIAL', false)")
        .param("id", gameId)
        .update();
    return gameId;
  }

  private void activateVersion(UUID gameId, String version) {
    jdbcClient
        .sql(
            "update game_version set status = 'SUPERSEDED', superseded_at = now()"
                + " where game_id = :id and status = 'ACTIVE'")
        .param("id", gameId)
        .update();
    UUID versionId = UUID.randomUUID();
    jdbcClient
        .sql(
            "insert into game_version (version_id, game_id, version, status, manifest,"
                + " declared_size_bytes, upload_expires_at, min_players, max_players, supports_ai,"
                + " supports_spectators, supports_mid_game_join, activated_at)"
                + " values (:id, :gameId, :version, 'ACTIVE', '{}'::jsonb, 1024, now(), 2, 2, false,"
                + " false, false, now())")
        .param("id", versionId)
        .param("gameId", gameId)
        .param("version", version)
        .update();
    jdbcClient
        .sql("update game set active_version_id = :vid where game_id = :id")
        .param("vid", versionId)
        .param("id", gameId)
        .update();
  }

  private static final class NoOpResponseErrorHandler
      implements org.springframework.web.client.ResponseErrorHandler {
    @Override
    public boolean hasError(org.springframework.http.client.ClientHttpResponse response) {
      return false;
    }

    @Override
    public void handleError(
        java.net.URI url,
        org.springframework.http.HttpMethod method,
        org.springframework.http.client.ClientHttpResponse response) {
      // Never called: hasError() always returns false so assertions see the real status.
    }
  }
}
