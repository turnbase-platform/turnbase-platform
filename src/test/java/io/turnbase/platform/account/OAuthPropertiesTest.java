package io.turnbase.platform.account;

import static org.assertj.core.api.Assertions.assertThat;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class OAuthPropertiesTest {

  private static OAuthProperties properties(
      @Nullable String clientId, @Nullable String clientSecret) {
    return new OAuthProperties(
        clientId,
        clientSecret,
        "https://accounts.google.com/o/oauth2/v2/auth",
        "https://oauth2.googleapis.com/token",
        "https://openidconnect.googleapis.com/v1/userinfo");
  }

  @Test
  void notConfiguredWhenBothAreNull() {
    assertThat(properties(null, null).configured()).isFalse();
  }

  @Test
  void notConfiguredWhenBothAreBlank() {
    assertThat(properties("", "").configured()).isFalse();
  }

  @Test
  void notConfiguredWhenOnlyOneIsSet() {
    assertThat(properties("client-id", null).configured()).isFalse();
    assertThat(properties(null, "secret").configured()).isFalse();
  }

  @Test
  void configuredWhenBothAreSet() {
    assertThat(properties("client-id", "secret").configured()).isTrue();
  }
}
