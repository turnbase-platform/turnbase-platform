package io.turnbase.platform.account;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** local-environment.md §7.4. */
@ConfigurationProperties(prefix = "turnbase.accounts")
public record AccountProperties(Duration renameWindow) {}
