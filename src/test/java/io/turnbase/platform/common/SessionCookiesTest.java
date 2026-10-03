package io.turnbase.platform.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class SessionCookiesTest {

  private static final Instant LATER = Instant.now().plusSeconds(3600);

  @Test
  void anHttpsOriginMakesTheSessionCookieSecure() {
    SessionCookies cookies =
        new SessionCookies(new PlatformOriginProperties("https://turnbase.io"));

    assertThat(cookies.issue("tok", LATER).toString())
        .contains("turnbase_session=tok")
        .contains("HttpOnly")
        .contains("Secure")
        .contains("SameSite=Strict")
        .contains("Path=/");
    assertThat(cookies.clear().toString()).contains("Secure").contains("Max-Age=0");
  }

  @Test
  void aPlainHttpOriginLeavesSecureOffSoTheLocalStackWorksInEveryBrowser() {
    SessionCookies cookies =
        new SessionCookies(new PlatformOriginProperties("http://turnbase.localhost:8080"));

    assertThat(cookies.issue("tok", LATER).toString())
        .contains("HttpOnly")
        .doesNotContain("Secure");
    assertThat(cookies.clear().toString()).doesNotContain("Secure");
  }
}
