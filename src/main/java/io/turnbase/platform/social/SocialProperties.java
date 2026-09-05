package io.turnbase.platform.social;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** local-environment.md §7.4. */
@ConfigurationProperties(prefix = "turnbase.social")
public record SocialProperties(int friendCap) {}
