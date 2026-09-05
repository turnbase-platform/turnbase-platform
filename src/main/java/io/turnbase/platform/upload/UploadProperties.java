package io.turnbase.platform.upload;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/**
 * local-environment.md §7.5 and the {@code turnbase.uploads.*} row of §7.2. Kept separate from
 * {@link ArtifactStoreProperties} — that one is the object-store connection, this one is the
 * upload-pipeline's own tunables.
 */
@ConfigurationProperties(prefix = "turnbase.uploads")
public record UploadProperties(
    DataSize maxBundleSize, Duration slotTtl, Duration validationLease) {}
