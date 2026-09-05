package io.turnbase.platform.account;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * secrets-and-configuration.md §5. Both fields are {@code @Nullable} — the client secret, and
 * therefore Google login (FR-AUTH-007), is absent by default; the login page hides the button
 * rather than offering one that fails, and this process starts anyway.
 */
@ConfigurationProperties(prefix = "turnbase.oauth.google")
public record OAuthProperties(@Nullable String clientId, @Nullable String clientSecret) {

  public boolean configured() {
    return clientId != null
        && !clientId.isBlank()
        && clientSecret != null
        && !clientSecret.isBlank();
  }
}
