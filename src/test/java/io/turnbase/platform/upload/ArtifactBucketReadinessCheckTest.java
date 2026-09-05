package io.turnbase.platform.upload;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketResponse;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;

class ArtifactBucketReadinessCheckTest {

  private final S3Client s3Client = mock(S3Client.class);
  private final ArtifactStoreProperties properties =
      new ArtifactStoreProperties("http://minio:9000", "turnbase-bundles", "key", "secret");
  private final ArtifactBucketReadinessCheck check =
      new ArtifactBucketReadinessCheck(s3Client, properties);

  @Test
  void succeedsWhenTheBucketExists() {
    when(s3Client.headBucket(any(HeadBucketRequest.class)))
        .thenReturn(HeadBucketResponse.builder().build());

    assertThatCode(() -> check.run(new DefaultApplicationArguments())).doesNotThrowAnyException();
  }

  @Test
  void failsStartupNamingTheMissingBucket() {
    when(s3Client.headBucket(any(HeadBucketRequest.class)))
        .thenThrow(NoSuchBucketException.builder().message("boom").build());

    assertThatThrownBy(() -> check.run(new DefaultApplicationArguments()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("turnbase-bundles")
        .hasMessageContaining("TURNBASE_ARTIFACT_STORE_BUCKET");
  }

  @Test
  void neverCreatesTheBucketItself() {
    when(s3Client.headBucket(any(HeadBucketRequest.class)))
        .thenReturn(HeadBucketResponse.builder().build());

    check.run(new DefaultApplicationArguments());

    verify(s3Client, never()).createBucket(any(CreateBucketRequest.class));
  }
}
