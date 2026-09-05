package io.turnbase.platform.account;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class OAuthPropertiesTest {

  @Test
  void notConfiguredWhenBothAreNull() {
    assertThat(new OAuthProperties(null, null).configured()).isFalse();
  }

  @Test
  void notConfiguredWhenBothAreBlank() {
    assertThat(new OAuthProperties("", "").configured()).isFalse();
  }

  @Test
  void notConfiguredWhenOnlyOneIsSet() {
    assertThat(new OAuthProperties("client-id", null).configured()).isFalse();
    assertThat(new OAuthProperties(null, "secret").configured()).isFalse();
  }

  @Test
  void configuredWhenBothAreSet() {
    assertThat(new OAuthProperties("client-id", "secret").configured()).isTrue();
  }
}
