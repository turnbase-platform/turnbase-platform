package io.turnbase.platform.upload;

import java.net.URI;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * The v2 S3 client talks to both MinIO (local, Testcontainers) and AWS S3 (production) via an
 * endpoint override (file-uploads.md §1) — {@code forcePathStyle} is required for MinIO, which does
 * not support virtual-hosted-style addressing by default. {@link ArtifactStoreProperties} is
 * registered by {@code TurnBaseApplication}'s {@code @ConfigurationPropertiesScan}, not here.
 *
 * <p>{@link S3Presigner} (IMP-M4-03) is the pre-signed-PUT half of the same client contract — it
 * needs the identical endpoint override, region and credentials as {@link S3Client}, so it is
 * configured alongside it rather than by {@link S3ArtifactStore} itself.
 */
@Configuration
public class ArtifactStoreConfig {

  @Bean
  public S3Client artifactStoreClient(ArtifactStoreProperties properties) {
    return S3Client.builder()
        .endpointOverride(URI.create(properties.endpoint()))
        .region(Region.US_EAST_1)
        .credentialsProvider(
            StaticCredentialsProvider.create(
                AwsBasicCredentials.create(properties.accessKey(), properties.secretKey())))
        .forcePathStyle(true)
        .build();
  }

  @Bean
  public S3Presigner artifactStorePresigner(ArtifactStoreProperties properties) {
    return S3Presigner.builder()
        .endpointOverride(URI.create(properties.endpoint()))
        .region(Region.US_EAST_1)
        .credentialsProvider(
            StaticCredentialsProvider.create(
                AwsBasicCredentials.create(properties.accessKey(), properties.secretKey())))
        .build();
  }
}
