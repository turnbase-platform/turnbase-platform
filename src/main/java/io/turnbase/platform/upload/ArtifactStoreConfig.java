package io.turnbase.platform.upload;

import java.net.URI;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * The v2 S3 client talks to both MinIO (local, Testcontainers) and AWS S3 (production) via an
 * endpoint override (file-uploads.md §1) — {@code forcePathStyle} is required for MinIO, which does
 * not support virtual-hosted-style addressing by default.
 */
@Configuration
@EnableConfigurationProperties(ArtifactStoreProperties.class)
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
}
