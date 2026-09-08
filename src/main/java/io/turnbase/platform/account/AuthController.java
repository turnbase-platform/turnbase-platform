package io.turnbase.platform.account;

import io.turnbase.platform.common.AuthenticatedUser;
import io.turnbase.platform.common.SessionCookie;
import io.turnbase.platform.common.UnauthenticatedException;
import io.turnbase.platform.session.SessionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.security.Principal;
import java.time.Duration;
import java.time.Instant;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code rest-conventions.md} § Authentication ("the session lifecycle is four endpoints"), {@code
 * flows/01-arrival-and-account.md} §D. Registration and login are exempt from authentication per
 * NFR-SEC-006; {@code session} and {@code logout} require it — {@code
 * session.SessionAuthenticationInterceptor} (IMP-M1-06) enforces that before either method runs.
 */
@RestController
@RequestMapping("/api/auth")
class AuthController {

  private final RegistrationService registrationService;
  private final LoginService loginService;
  private final SessionService sessionService;
  private final AccountRepository accountRepository;

  AuthController(
      RegistrationService registrationService,
      LoginService loginService,
      SessionService sessionService,
      AccountRepository accountRepository) {
    this.registrationService = registrationService;
    this.loginService = loginService;
    this.sessionService = sessionService;
    this.accountRepository = accountRepository;
  }

  @PostMapping("/register")
  ResponseEntity<PrincipalResponse> register(@Valid @RequestBody RegisterRequest request) {
    RegistrationService.RegistrationResult result = registrationService.register(request, null);
    return ResponseEntity.status(HttpStatus.CREATED)
        .location(URI.create("/api/users/" + result.username()))
        .header(HttpHeaders.SET_COOKIE, sessionCookie(result.session()).toString())
        .body(new PrincipalResponse(result.userId(), result.username()));
  }

  @PostMapping("/login")
  ResponseEntity<PrincipalResponse> login(@Valid @RequestBody LoginRequest request) {
    LoginService.LoginResult result = loginService.login(request, null);
    return ResponseEntity.ok()
        .header(HttpHeaders.SET_COOKIE, sessionCookie(result.session()).toString())
        .body(new PrincipalResponse(result.userId(), result.username()));
  }

  /** {@code rest-conventions.md}: "the frontend's tier check on load." */
  @GetMapping("/session")
  ResponseEntity<PrincipalResponse> session(HttpServletRequest request) {
    AuthenticatedUser principal = principalOf(request);
    String username =
        accountRepository
            .findUsernameById(principal.userId())
            .orElseThrow(() -> new UnauthenticatedException("This account no longer exists."));
    return ResponseEntity.ok(new PrincipalResponse(principal.userId(), username));
  }

  /** FR-SESS-011: revokes the current session and closes its live connections (FR-SESS-010). */
  @PostMapping("/logout")
  ResponseEntity<Void> logout(HttpServletRequest request) {
    AuthenticatedUser principal = principalOf(request);
    sessionService.revoke(principal.sessionId(), principal.userId(), SessionService.LOGOUT_REASON);
    return ResponseEntity.noContent()
        .header(HttpHeaders.SET_COOKIE, clearedSessionCookie().toString())
        .build();
  }

  /** {@code session.SessionAuthenticationInterceptor} has already required this to be non-null. */
  private static AuthenticatedUser principalOf(HttpServletRequest request) {
    Principal principal = request.getUserPrincipal();
    if (!(principal instanceof AuthenticatedUser authenticatedUser)) {
      throw new IllegalStateException(
          "SessionAuthenticationInterceptor must reject an unauthenticated request to this"
              + " endpoint before its handler runs");
    }
    return authenticatedUser;
  }

  /** {@code user-accounts.md} CON-004, {@code rest-conventions.md} § Authentication. */
  private static ResponseCookie sessionCookie(SessionService.IssuedSession session) {
    return ResponseCookie.from(SessionCookie.NAME, session.token())
        .httpOnly(true)
        .secure(true)
        .sameSite("Strict")
        .path("/")
        .maxAge(Duration.between(Instant.now(), session.expiresAt()))
        .build();
  }

  private static ResponseCookie clearedSessionCookie() {
    return ResponseCookie.from(SessionCookie.NAME, "")
        .httpOnly(true)
        .secure(true)
        .sameSite("Strict")
        .path("/")
        .maxAge(0)
        .build();
  }
}
