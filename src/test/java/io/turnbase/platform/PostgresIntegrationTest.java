package io.turnbase.platform;

import java.net.URI;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;

/**
 * Shared Postgres 16 and MinIO containers for *IT classes that boot a real Spring context: Flyway
 * migrates against Postgres, and {@link io.turnbase.platform.upload.ArtifactBucketReadinessCheck}
 * asserts the bundle bucket exists against MinIO at startup (local-environment.md §5) — a full boot
 * needs both.
 *
 * <p>The Testcontainers JUnit extension restarts a shared static container fresh for each test
 * class that declares it (it is "shared" only within one class, not across classes sharing this
 * base) — a bucket created once and never again would exist for whichever subclass runs first and
 * be gone for the rest. {@link #createArtifactBucket()} therefore runs before every subclass and is
 * idempotent, matching {@code minio-init}'s own {@code --ignore-existing} (local-environment.md
 * §5).
 */
@Testcontainers
public abstract class PostgresIntegrationTest {

  protected static final String ARTIFACT_BUCKET = "turnbase-bundles";

  @Container @ServiceConnection
  protected static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine");

  // IMP-GATE-52: MinIO no longer publishes to Docker Hub, so the image is pulled from its current
  // registry — Testcontainers' MinIOContainer validates the image name against the bare
  // "minio/minio" repository regardless of host, hence the explicit substitute declaration.
  @Container
  protected static final MinIOContainer MINIO =
      new MinIOContainer(
          DockerImageName.parse("quay.io/minio/minio:RELEASE.2025-09-07T16-13-09Z")
              .asCompatibleSubstituteFor("minio/minio"));

  // No official Testcontainers Redis module exists; a plain GenericContainer with an explicit
  // @ServiceConnection name is Spring Boot's own documented way to wire one (local-environment.md
  // §8, "Testcontainers with @ServiceConnection"). Only wires a genuine Spring TestContext, which
  // none of this base class's manual-boot subclasses create — each passes the URL explicitly via
  // redisUrl() below, the same way POSTGRES's URL is passed rather than relied on implicitly.
  @Container
  @ServiceConnection(name = "redis")
  protected static final GenericContainer<?> REDIS =
      new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

  protected static String redisUrl() {
    return "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379);
  }

  @BeforeAll
  static void createArtifactBucket() {
    try (S3Client client =
        S3Client.builder()
            .endpointOverride(URI.create(MINIO.getS3URL()))
            .region(Region.US_EAST_1)
            .credentialsProvider(
                StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(MINIO.getUserName(), MINIO.getPassword())))
            .forcePathStyle(true)
            .build()) {
      client.createBucket(CreateBucketRequest.builder().bucket(ARTIFACT_BUCKET).build());
    } catch (BucketAlreadyOwnedByYouException e) {
      // Already created by an earlier subclass sharing the same (not-yet-restarted) container.
    }
  }
}
