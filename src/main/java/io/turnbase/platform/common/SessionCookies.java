package io.turnbase.platform.common;

import java.time.Duration;
import java.time.Instant;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * Builds the {@code turnbase_session} cookie ({@code rest-conventions.md} § Authentication). The
 * {@code Secure} flag follows the configured platform origin: {@code https://} deployments set it,
 * and the plain-HTTP local stack does not, because browsers that do not treat {@code localhost} as
 * a secure context (Safari, older Firefox) drop a {@code Secure} cookie received over HTTP.
 */
@Component
public class SessionCookies {

  private final boolean secure;

  public SessionCookies(PlatformOriginProperties platformOrigin) {
    this.secure = platformOrigin.origin().startsWith("https://");
  }

  /** The cookie that carries a freshly issued session until {@code expiresAt}. */
  public ResponseCookie issue(String token, Instant expiresAt) {
    return builder(token).maxAge(Duration.between(Instant.now(), expiresAt)).build();
  }

  /** The cookie that makes the browser forget the session. */
  public ResponseCookie clear() {
    return builder("").maxAge(0).build();
  }

  private ResponseCookie.ResponseCookieBuilder builder(String value) {
    return ResponseCookie.from(SessionCookie.NAME, value)
        .httpOnly(true)
        .secure(secure)
        .sameSite("Strict")
        .path("/");
  }
}
