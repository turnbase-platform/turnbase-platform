package io.turnbase.platform.upload;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;

/**
 * local-environment.md §5: the application asserts the bundle bucket exists and never creates it —
 * a service that creates its own bucket needs bucket-creation rights it must not hold in
 * production. The bucket is created by the {@code minio-init} compose step, or the equivalent
 * Kubernetes {@code Job}, before this process ever starts.
 */
@Component
public class ArtifactBucketReadinessCheck implements ApplicationRunner {

  private final S3Client artifactStoreClient;
  private final ArtifactStoreProperties properties;

  public ArtifactBucketReadinessCheck(
      S3Client artifactStoreClient, ArtifactStoreProperties properties) {
    this.artifactStoreClient = artifactStoreClient;
    this.properties = properties;
  }

  @Override
  public void run(ApplicationArguments args) {
    try {
      artifactStoreClient.headBucket(
          HeadBucketRequest.builder().bucket(properties.bucket()).build());
    } catch (NoSuchBucketException e) {
      throw new IllegalStateException(
          "Artifact store bucket '"
              + properties.bucket()
              + "' does not exist. Set TURNBASE_ARTIFACT_STORE_BUCKET to an existing bucket, or"
              + " run the minio-init step (local-environment.md §5) before starting the"
              + " application.",
          e);
    }
  }
}
