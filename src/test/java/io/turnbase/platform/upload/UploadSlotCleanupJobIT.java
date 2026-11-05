package io.turnbase.platform.upload;

import static org.assertj.core.api.Assertions.assertThat;

import io.turnbase.platform.PostgresIntegrationTest;
import java.net.URI;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code bundle-format.md} §7.2's sweep duties 1, 3 and 4 — duty 2 (the lease-expiry fix for a
 * died-mid-validation version number) is proven at the full HTTP stack level by {@link
 * VersionUploadControllerIT#theSweepFreesAnAbandonedLeaseAndTheVersionNumberCanBeRetried}.
 *
 * <p>Requirements protected: {@code platform-operations.md} FR-JOB-012; {@code
 * game-development-experience.md} NFR-UPLOAD-001.
 */
class UploadSlotCleanupJobIT extends PostgresIntegrationTest {

  private static JdbcClient jdbcClient;
  private static GameVersionRepository repository;
  private static ArtifactStore artifactStore;
  private static UploadSlotCleanupJob job;

  @BeforeAll
  static void setUp() {
    Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .configuration(java.util.Map.of("flyway.postgresql.transactional.lock", "false"))
        .load()
        .migrate();
    DriverManagerDataSource dataSource =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    jdbcClient = JdbcClient.create(dataSource);
    repository = new GameVersionRepository(jdbcClient, JsonMapper.builder().build());

    ArtifactStoreProperties properties =
        new ArtifactStoreProperties(
            MINIO.getS3URL(), ARTIFACT_BUCKET, MINIO.getUserName(), MINIO.getPassword());
    S3Client s3Client =
        S3Client.builder()
            .endpointOverride(URI.create(properties.endpoint()))
            .region(Region.US_EAST_1)
            .credentialsProvider(
                StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(properties.accessKey(), properties.secretKey())))
            .forcePathStyle(true)
            .build();
    S3Presigner presigner =
        S3Presigner.builder()
            .endpointOverride(URI.create(properties.endpoint()))
            .region(Region.US_EAST_1)
            .credentialsProvider(
                StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(properties.accessKey(), properties.secretKey())))
            .build();
    artifactStore = new S3ArtifactStore(s3Client, presigner, properties);
    job = new UploadSlotCleanupJob(repository, artifactStore);
  }

  @BeforeEach
  void cleanTables() {
    jdbcClient.sql("truncate game_tag, game_version, game, app_user cascade").update();
  }

  @Test
  void duty1_expiredPendingSlotBecomesExpiredAndItsBundleIsPurged() {
    UUID gameId = insertGame();
    UUID versionId = UUID.randomUUID();
    String key = ObjectKeys.bundle(gameId, "1.0.0");
    artifactStore.put(
        key, "bundle bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8), "application/zip");
    jdbcClient
        .sql(
            "insert into game_version (version_id, game_id, version, status, bundle_object_key, declared_size_bytes, upload_expires_at)"
                + " values (:id, :gameId, '1.0.0', 'PENDING', :key, 1000, :expired)")
        .param("id", versionId)
        .param("gameId", gameId)
        .param("key", key)
        .param("expired", Timestamp.from(Instant.now().minusSeconds(3600)))
        .update();

    job.run();

    GameVersionRow row = repository.findByVersionId(versionId).orElseThrow();
    assertThat(row.status()).isEqualTo(GameVersionStatus.EXPIRED);
    assertThat(artifactStore.sizeOf(key)).isEmpty();
  }

  @Test
  void duty3_aFailedRowsBundleIsPurgedAndMarkedSo() {
    UUID gameId = insertGame();
    UUID versionId = UUID.randomUUID();
    String key = ObjectKeys.bundle(gameId, "1.0.0");
    artifactStore.put(
        key, "bundle bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8), "application/zip");
    jdbcClient
        .sql(
            "insert into game_version (version_id, game_id, version, status, bundle_object_key, declared_size_bytes, upload_expires_at, validation_errors)"
                + " values (:id, :gameId, '1.0.0', 'FAILED', :key, 1000, now(), '[]'::jsonb)")
        .param("id", versionId)
        .param("gameId", gameId)
        .param("key", key)
        .update();

    job.run();

    assertThat(artifactStore.sizeOf(key)).isEmpty();
    Timestamp purgedAt =
        jdbcClient
            .sql("select bundle_purged_at from game_version where version_id = :id")
            .param("id", versionId)
            .query(Timestamp.class)
            .single();
    assertThat(purgedAt).isNotNull();
  }

  @Test
  void duty4_orphanedUnpackedAndArtifactTreesAreDeletedForANonActiveVersion() {
    UUID gameId = insertGame();
    UUID versionId = UUID.randomUUID();
    artifactStore.put(
        ObjectKeys.unpacked(gameId, "1.0.0", "frontend/main.js"),
        "console.log(1)".getBytes(java.nio.charset.StandardCharsets.UTF_8),
        "text/javascript");
    artifactStore.put(
        ObjectKeys.backendJar(gameId, "1.0.0"),
        "jar bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8),
        "application/java-archive");
    jdbcClient
        .sql(
            "insert into game_version (version_id, game_id, version, status, declared_size_bytes, upload_expires_at, validation_errors)"
                + " values (:id, :gameId, '1.0.0', 'FAILED', 1000, now(), '[]'::jsonb)")
        .param("id", versionId)
        .param("gameId", gameId)
        .update();

    job.run();

    assertThat(artifactStore.listKeys(ObjectKeys.unpackedPrefix(gameId, "1.0.0"))).isEmpty();
    assertThat(artifactStore.listKeys(ObjectKeys.artifactsPrefix(gameId, "1.0.0"))).isEmpty();
  }

  @Test
  void anActiveVersionsUnpackedTreeIsNeverTouched() {
    UUID gameId = insertGame();
    UUID versionId = UUID.randomUUID();
    artifactStore.put(
        ObjectKeys.unpacked(gameId, "1.0.0", "frontend/main.js"),
        "console.log(1)".getBytes(java.nio.charset.StandardCharsets.UTF_8),
        "text/javascript");
    jdbcClient
        .sql(
            "insert into game_version (version_id, game_id, version, status, declared_size_bytes, upload_expires_at, activated_at)"
                + " values (:id, :gameId, '1.0.0', 'ACTIVE', 1000, now(), now())")
        .param("id", versionId)
        .param("gameId", gameId)
        .update();

    job.run();

    assertThat(artifactStore.listKeys(ObjectKeys.unpackedPrefix(gameId, "1.0.0"))).isNotEmpty();
  }

  private UUID insertGame() {
    UUID gameId = UUID.randomUUID();
    jdbcClient
        .sql(
            "insert into game (game_id, owner_user_id, title, short_description, badge, listed) values (:id, null, 'Untitled', 'placeholder', 'OFFICIAL', false)")
        .param("id", gameId)
        .update();
    return gameId;
  }
}
