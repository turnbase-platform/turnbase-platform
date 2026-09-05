package io.turnbase.platform.common;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/**
 * local-environment.md §7.5's request-body cap — four orders of magnitude below {@code
 * turnbase.uploads.max-bundle-size} because a bundle upload never passes through this process (it
 * is PUT directly to object storage against a pre-signed URL).
 */
@ConfigurationProperties(prefix = "turnbase.http")
public record HttpProperties(DataSize maxBodySize) {}
