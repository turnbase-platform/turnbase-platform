package io.turnbase.platform.upload;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;

/**
 * Proves the bucket assertion (local-environment.md §5) against a real MinIO — conventions.md §3:
 * Testcontainers, never a hand-started stack.
 */
@Testcontainers
class ArtifactBucketReadinessCheckIT {

  // IMP-GATE-52: see PostgresIntegrationTest's identical container declaration.
  @Container
  private static final MinIOContainer MINIO =
      new MinIOContainer(
          DockerImageName.parse("quay.io/minio/minio:RELEASE.2025-09-07T16-13-09Z")
              .asCompatibleSubstituteFor("minio/minio"));

  private S3Client client() {
    return S3Client.builder()
        .endpointOverride(URI.create(MINIO.getS3URL()))
        .region(Region.US_EAST_1)
        .credentialsProvider(
            StaticCredentialsProvider.create(
                AwsBasicCredentials.create(MINIO.getUserName(), MINIO.getPassword())))
        .forcePathStyle(true)
        .build();
  }

  @Test
  void succeedsWhenTheBucketExists() {
    try (S3Client client = client()) {
      client.createBucket(CreateBucketRequest.builder().bucket("turnbase-bundles-present").build());
      var check =
          new ArtifactBucketReadinessCheck(
              client,
              new ArtifactStoreProperties(
                  MINIO.getS3URL(),
                  "turnbase-bundles-present",
                  MINIO.getUserName(),
                  MINIO.getPassword()));

      assertThatCode(() -> check.run(new DefaultApplicationArguments())).doesNotThrowAnyException();
    }
  }

  @Test
  void failsStartupNamingTheMissingBucket() {
    try (S3Client client = client()) {
      var check =
          new ArtifactBucketReadinessCheck(
              client,
              new ArtifactStoreProperties(
                  MINIO.getS3URL(), "does-not-exist", MINIO.getUserName(), MINIO.getPassword()));

      assertThatThrownBy(() -> check.run(new DefaultApplicationArguments()))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("does-not-exist")
          .hasMessageContaining("TURNBASE_ARTIFACT_STORE_BUCKET");
    }
  }
}
