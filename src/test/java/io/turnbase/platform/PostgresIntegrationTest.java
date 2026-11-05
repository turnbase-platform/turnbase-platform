package io.turnbase.platform;

import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.Bucket;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.S3Object;

/**
 * One Postgres 16, one MinIO and one Redis per test JVM (the singleton container pattern,
 * IMP-M20-09), started once by the static initialiser and reaped by Testcontainers' Ryuk at JVM
 * exit. Every *IT class that boots a real Spring context extends this: Flyway migrates against
 * Postgres, and {@link io.turnbase.platform.upload.ArtifactBucketReadinessCheck} asserts the bundle
 * bucket exists against MinIO at startup (local-environment.md §5) — a full boot needs both.
 *
 * <p>Sharing the containers must not share state. {@link #resetSharedState()} runs before every
 * subclass and gives it what a freshly started container used to: an empty {@code public} schema
 * (Flyway rebuilds it, so each class is still a migration test), an empty Redis, and a MinIO
 * holding only the empty {@link #ARTIFACT_BUCKET}. A subclass that needs a state finer than per
 * class (a schema reset per test) still does that itself.
 */
public abstract class PostgresIntegrationTest {

  protected static final String ARTIFACT_BUCKET = "turnbase-bundles";

  protected static final PostgreSQLContainer<?> POSTGRES;
  protected static final MinIOContainer MINIO;
  protected static final GenericContainer<?> REDIS;

  static {
    POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    // IMP-GATE-73: MinIO publishes no public image any more (Docker Hub, IMP-GATE-52, then
    // quay.io),
    // so the image is the pgsty community fork local-environment.md §1 names — Testcontainers'
    // MinIOContainer validates the image name against "minio/minio", hence the substitute
    // declaration.
    MINIO =
        new MinIOContainer(
            DockerImageName.parse("pgsty/minio:RELEASE.2026-08-04T00-00-00Z")
                .asCompatibleSubstituteFor("minio/minio"));
    // No official Testcontainers Redis module exists; a plain GenericContainer is the documented
    // route (local-environment.md §8). Subclasses boot the application by hand and pass the URL
    // explicitly via redisUrl(), the same way POSTGRES's URL is passed.
    REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);
    // Started in parallel: the three images are independent and start-up dominates a cold run.
    java.util.stream.Stream.of(POSTGRES, MINIO, REDIS)
        .parallel()
        .forEach(org.testcontainers.lifecycle.Startable::start);
  }

  protected static String redisUrl() {
    return "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379);
  }

  @BeforeAll
  static void resetSharedState() throws Exception {
    try (Connection connection =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var statement = connection.createStatement()) {
      statement.execute("drop schema public cascade");
      statement.execute("create schema public");
    } catch (SQLException e) {
      throw new IllegalStateException("could not reset the shared Postgres schema", e);
    }
    REDIS.execInContainer("redis-cli", "FLUSHALL");
    resetArtifactBucket();
  }

  private static void resetArtifactBucket() {
    try (S3Client client =
        S3Client.builder()
            .endpointOverride(URI.create(MINIO.getS3URL()))
            .region(Region.US_EAST_1)
            .credentialsProvider(
                StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(MINIO.getUserName(), MINIO.getPassword())))
            .forcePathStyle(true)
            .build()) {
      for (Bucket bucket : client.listBuckets().buckets()) {
        for (S3Object object :
            client
                .listObjectsV2Paginator(
                    ListObjectsV2Request.builder().bucket(bucket.name()).build())
                .contents()) {
          client.deleteObject(
              DeleteObjectRequest.builder().bucket(bucket.name()).key(object.key()).build());
        }
        client.deleteBucket(DeleteBucketRequest.builder().bucket(bucket.name()).build());
      }
      client.createBucket(CreateBucketRequest.builder().bucket(ARTIFACT_BUCKET).build());
    }
  }
}
