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
        .contains("__Host-turnbase_session=tok")
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

  @Test
  void theCookieNameFollowsTheOriginAndAHostPrefixedCookieHasNoDomainAndRootPath() {
    SessionCookies tls = new SessionCookies(new PlatformOriginProperties("https://turnbase.io"));
    SessionCookies plain =
        new SessionCookies(new PlatformOriginProperties("http://turnbase.localhost:8080"));

    assertThat(tls.name()).isEqualTo("__Host-turnbase_session");
    assertThat(tls.issue("tok", LATER).toString()).doesNotContain("Domain");
    assertThat(tls.clear().toString()).startsWith("__Host-turnbase_session=;");
    assertThat(plain.name()).isEqualTo("turnbase_session");
    assertThat(plain.issue("tok", LATER).toString()).startsWith("turnbase_session=tok");
  }
}
