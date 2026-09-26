package io.turnbase.platform.account;

import io.turnbase.platform.common.AuthenticatedUser;
import io.turnbase.platform.common.OAuthLinkCookie;
import io.turnbase.platform.common.OAuthPendingCookie;
import io.turnbase.platform.common.SessionCookie;
import io.turnbase.platform.session.SessionService;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code oauth-rest-api.md} §3/§4 — the two browser-navigated OAuth routes. Neither is under {@code
 * /api/}: {@code /auth/callback}'s path is fixed by the redirect URI Google has registered for us
 * ({@code secrets-and-configuration.md} §5), and {@code /auth/google} sits alongside it rather than
 * splitting the entry surface across two prefixes for no reason. NFR-SEC-006 exempts both
 * (IMP-GATE-38, {@code session.SessionAuthenticationInterceptor}).
 */
@RestController
class OAuthController {

  private static final String LINK_INTENT = "link";

  private final OAuthService oauthService;
  private final OAuthProperties oauthProperties;

  OAuthController(OAuthService oauthService, OAuthProperties oauthProperties) {
    this.oauthService = oauthService;
    this.oauthProperties = oauthProperties;
  }

  /**
   * §1 ({@code IMP-GATE-76}) — whether the login and register pages should render "Continue with
   * Google". Unauthenticated (NFR-SEC-006) and Tier 4; the answer never discloses the client id or
   * secret.
   */
  @GetMapping("/api/auth/providers")
  ProvidersResponse providers() {
    return new ProvidersResponse(oauthProperties.configured());
  }

  /** The body of {@code GET /api/auth/providers}. */
  record ProvidersResponse(boolean google) {}

  /**
   * §3. {@code intent=link} is §7a's settings-page variant and reads the session; a plain login
   * initiation does not.
   */
  @GetMapping("/auth/google")
  ResponseEntity<Void> initiate(
      @RequestParam(required = false) @Nullable String redirect,
      @RequestParam(required = false) @Nullable String intent,
      HttpServletRequest request) {
    URI location =
        LINK_INTENT.equals(intent)
            ? oauthService.initiateLink(callerOf(request))
            : oauthService.initiate(redirect);
    return ResponseEntity.status(HttpStatus.FOUND).location(location).build();
  }

  /** §4. */
  @GetMapping("/auth/callback")
  ResponseEntity<Void> callback(
      @RequestParam(required = false) String code, @RequestParam(required = false) String state) {
    OAuthService.CallbackResult result = oauthService.callback(code, state);
    return switch (result) {
      case OAuthService.CallbackResult.Error ignored ->
          ResponseEntity.status(HttpStatus.FOUND)
              .location(URI.create("/login?oauthError=1"))
              .build();
      case OAuthService.CallbackResult.LoggedIn loggedIn ->
          ResponseEntity.status(HttpStatus.FOUND)
              .location(URI.create(loggedIn.destination()))
              .header(HttpHeaders.SET_COOKIE, sessionCookie(loggedIn.session()).toString())
              .build();
      case OAuthService.CallbackResult.LinkError ignored ->
          ResponseEntity.status(HttpStatus.FOUND)
              .location(URI.create("/settings?linkError=1"))
              .build();
      case OAuthService.CallbackResult.LinkPending linkPending ->
          ResponseEntity.status(HttpStatus.FOUND)
              .location(URI.create("/settings?linkGoogle=1"))
              .header(
                  HttpHeaders.SET_COOKIE,
                  linkCookie(linkPending.token(), linkPending.expiresAt()).toString())
              .build();
      case OAuthService.CallbackResult.PendingRegistration pendingRegistration ->
          ResponseEntity.status(HttpStatus.FOUND)
              .location(URI.create("/auth/complete-registration"))
              .header(
                  HttpHeaders.SET_COOKIE,
                  pendingCookie(pendingRegistration.token(), pendingRegistration.expiresAt())
                      .toString())
              .build();
    };
  }

  /** {@code user-accounts.md} CON-004, {@code rest-conventions.md} § Authentication. */
  static ResponseCookie sessionCookie(SessionService.IssuedSession session) {
    return ResponseCookie.from(SessionCookie.NAME, session.token())
        .httpOnly(true)
        .secure(true)
        .sameSite("Strict")
        .path("/")
        .maxAge(Duration.between(Instant.now(), session.expiresAt()))
        .build();
  }

  /** {@code oauth-rest-api.md} §2 — the same flag set as the session cookie. */
  static ResponseCookie pendingCookie(String token, Instant expiresAt) {
    return ResponseCookie.from(OAuthPendingCookie.NAME, token)
        .httpOnly(true)
        .secure(true)
        .sameSite("Strict")
        .path("/")
        .maxAge(Duration.between(Instant.now(), expiresAt))
        .build();
  }

  /** {@code oauth-rest-api.md} §7a — same flag set and reasoning as §2's pending cookie. */
  static ResponseCookie linkCookie(String token, Instant expiresAt) {
    return ResponseCookie.from(OAuthLinkCookie.NAME, token)
        .httpOnly(true)
        .secure(true)
        .sameSite("Strict")
        .path("/")
        .maxAge(Duration.between(Instant.now(), expiresAt))
        .build();
  }

  static ResponseCookie clearedLinkCookie() {
    return ResponseCookie.from(OAuthLinkCookie.NAME, "")
        .httpOnly(true)
        .secure(true)
        .sameSite("Strict")
        .path("/")
        .maxAge(0)
        .build();
  }

  private static @Nullable UUID callerOf(HttpServletRequest request) {
    return request.getUserPrincipal() instanceof AuthenticatedUser user ? user.userId() : null;
  }

  static ResponseCookie clearedPendingCookie() {
    return ResponseCookie.from(OAuthPendingCookie.NAME, "")
        .httpOnly(true)
        .secure(true)
        .sameSite("Strict")
        .path("/")
        .maxAge(0)
        .build();
  }
}
