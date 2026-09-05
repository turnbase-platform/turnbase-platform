package io.turnbase.platform.upload;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Endpoint and bucket are the non-secret tunables of local-environment.md §7.9; access/secret key
 * are the MinIO credential of secrets-and-configuration.md §4, bound under the same prefix.
 */
@ConfigurationProperties(prefix = "turnbase.artifact-store")
public record ArtifactStoreProperties(
    String endpoint, String bucket, String accessKey, String secretKey) {}
