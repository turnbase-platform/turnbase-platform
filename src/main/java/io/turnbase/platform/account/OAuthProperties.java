package io.turnbase.platform.account;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * secrets-and-configuration.md §5. {@code clientId}/{@code clientSecret} are {@code @Nullable} —
 * the client secret, and therefore Google login (FR-AUTH-007), is absent by default; the login page
 * hides the button rather than offering one that fails, and this process starts anyway.
 *
 * <p>The three endpoint fields are Google's own fixed URLs by default, not a per-environment
 * concern the way {@code artifact-store.endpoint} is — they are configurable purely so a test can
 * point {@link GoogleOAuthClient} at a local stub instead of the real Google, the same reason
 * {@code artifact-store.endpoint} can point at a Testcontainers MinIO.
 */
@ConfigurationProperties(prefix = "turnbase.oauth.google")
public record OAuthProperties(
    @Nullable String clientId,
    @Nullable String clientSecret,
    String authorizationEndpoint,
    String tokenEndpoint,
    String userinfoEndpoint) {

  public boolean configured() {
    return clientId != null
        && !clientId.isBlank()
        && clientSecret != null
        && !clientSecret.isBlank();
  }
}
