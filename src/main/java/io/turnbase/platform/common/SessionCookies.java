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
 *
 * <p>Under {@code https://} the cookie is also named with the {@code __Host-} prefix, so a browser
 * refuses one set by a sibling subdomain or without {@code Secure}, {@code Path=/} and a {@code
 * Domain}. Every server-side reader asks {@link #name()} rather than assuming a name.
 */
@Component
public class SessionCookies {

  private static final String HOST_PREFIX = "__Host-";

  private final boolean secure;
  private final String name;

  public SessionCookies(PlatformOriginProperties platformOrigin) {
    this.secure = platformOrigin.origin().startsWith("https://");
    this.name = secure ? HOST_PREFIX + SessionCookie.NAME : SessionCookie.NAME;
  }

  /** The name the browser stores and returns the session cookie under. */
  public String name() {
    return name;
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
    return ResponseCookie.from(name, value)
        .httpOnly(true)
        .secure(secure)
        .sameSite("Strict")
        .path("/");
  }
}
