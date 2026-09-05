package io.turnbase.platform.common;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The purge windows of local-environment.md §7.3. Cross-cutting for the same reason {@link
 * JobsProperties} is: the tables in §7.2/§7.3 group by concern, not by which feature will
 * eventually own the purge job.
 */
@ConfigurationProperties(prefix = "turnbase.retention")
public record RetentionProperties(
    Duration closedRoom, Duration notification, Duration friendRequest, Duration guestAccount) {}
