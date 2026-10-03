package io.turnbase.platform.account;

import io.turnbase.platform.common.OAuthPendingCookie;
import io.turnbase.platform.common.SessionCookies;
import io.turnbase.platform.session.SessionService;
import jakarta.validation.Valid;
import java.net.URI;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code oauth-rest-api.md} §5/§6/§7 — the three JSON endpoints the {@code
 * /auth/complete-registration} frontend page calls, gated by the pending cookie (§2) rather than a
 * session. NFR-SEC-006 exempts all three (IMP-GATE-38).
 */
@RestController
@RequestMapping("/api/auth/oauth")
class OAuthRegistrationController {

  private final OAuthService oauthService;
  private final SessionCookies sessionCookies;

  OAuthRegistrationController(OAuthService oauthService, SessionCookies sessionCookies) {
    this.oauthService = oauthService;
    this.sessionCookies = sessionCookies;
  }

  /** §5. */
  @GetMapping("/pending")
  ResponseEntity<OAuthPendingResponse> pending(
      @CookieValue(name = OAuthPendingCookie.NAME, required = false)
          @Nullable String pendingToken) {
    return ResponseEntity.ok(new OAuthPendingResponse(oauthService.readPendingEmail(pendingToken)));
  }

  /** §6. */
  @PostMapping("/complete-registration")
  ResponseEntity<?> completeRegistration(
      @Valid @RequestBody CompleteRegistrationRequest request,
      @CookieValue(name = OAuthPendingCookie.NAME, required = false)
          @Nullable String pendingToken) {
    OAuthService.CompleteRegistrationResult result =
        oauthService.completeRegistration(pendingToken, request.username());
    return switch (result) {
      case OAuthService.CompleteRegistrationResult.Created created ->
          ResponseEntity.status(HttpStatus.CREATED)
              .location(URI.create("/api/users/" + created.username()))
              .header(
                  HttpHeaders.SET_COOKIE,
                  sessionCookies
                      .issue(created.session().token(), created.session().expiresAt())
                      .toString())
              .header(HttpHeaders.SET_COOKIE, OAuthController.clearedPendingCookie().toString())
              .body(PrincipalResponse.registered(created.userId(), created.username()));
      case OAuthService.CompleteRegistrationResult.ChallengeRequired ignored ->
          ResponseEntity.ok(new OAuthOutcomeResponse(OAuthOutcomeResponse.CHALLENGE_REQUIRED));
    };
  }

  /** §7. */
  @PostMapping("/link-challenge")
  ResponseEntity<OAuthOutcomeResponse> linkChallenge(
      @Valid @RequestBody LinkChallengeRequest request,
      @CookieValue(name = OAuthPendingCookie.NAME, required = false)
          @Nullable String pendingToken) {
    SessionService.IssuedSession session =
        oauthService.linkChallenge(pendingToken, request.password());
    return ResponseEntity.ok()
        .header(
            HttpHeaders.SET_COOKIE,
            sessionCookies.issue(session.token(), session.expiresAt()).toString())
        .header(HttpHeaders.SET_COOKIE, OAuthController.clearedPendingCookie().toString())
        .body(new OAuthOutcomeResponse(OAuthOutcomeResponse.LINKED));
  }
}
