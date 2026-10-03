package io.turnbase.platform.account;

import io.turnbase.platform.common.AuthenticatedUser;
import io.turnbase.platform.common.SessionCookies;
import io.turnbase.platform.common.UnauthenticatedException;
import io.turnbase.platform.session.SessionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.security.Principal;
import java.util.Map;
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
 * flows/01-arrival-and-account.md} §D. Registration, login and guest creation are exempt from
 * authentication per NFR-SEC-006 (IMP-GATE-29 added guest creation to that list); {@code session}
 * and {@code logout} require it — {@code session.SessionAuthenticationInterceptor} (IMP-M1-06)
 * enforces that before either method runs.
 */
@RestController
@RequestMapping("/api/auth")
class AuthController {

  private static final Map<String, Boolean> ANONYMOUS = Map.of("authenticated", false);

  private final RegistrationService registrationService;
  private final LoginService loginService;
  private final GuestService guestService;
  private final SessionService sessionService;
  private final AccountRepository accountRepository;
  private final SessionCookies sessionCookies;

  AuthController(
      RegistrationService registrationService,
      LoginService loginService,
      GuestService guestService,
      SessionService sessionService,
      AccountRepository accountRepository,
      SessionCookies sessionCookies) {
    this.registrationService = registrationService;
    this.loginService = loginService;
    this.guestService = guestService;
    this.sessionService = sessionService;
    this.accountRepository = accountRepository;
    this.sessionCookies = sessionCookies;
  }

  @PostMapping("/register")
  ResponseEntity<PrincipalResponse> register(@Valid @RequestBody RegisterRequest request) {
    RegistrationService.RegistrationResult result = registrationService.register(request, null);
    return ResponseEntity.status(HttpStatus.CREATED)
        .location(URI.create("/api/users/" + result.username()))
        .header(HttpHeaders.SET_COOKIE, sessionCookie(result.session()).toString())
        .body(PrincipalResponse.registered(result.userId(), result.username()));
  }

  @PostMapping("/login")
  ResponseEntity<PrincipalResponse> login(@Valid @RequestBody LoginRequest request) {
    LoginService.LoginResult result = loginService.login(request, null);
    return ResponseEntity.ok()
        .header(HttpHeaders.SET_COOKIE, sessionCookie(result.session()).toString())
        .body(PrincipalResponse.registered(result.userId(), result.username()));
  }

  /** FR-GUEST-001/003: "Play as Guest" — unauthenticated (NFR-SEC-006), always succeeds. */
  @PostMapping("/guest")
  ResponseEntity<PrincipalResponse> guest() {
    GuestService.GuestResult result = guestService.mint(null);
    return ResponseEntity.status(HttpStatus.CREATED)
        .location(URI.create("/api/users/" + result.username()))
        .header(HttpHeaders.SET_COOKIE, sessionCookie(result.session()).toString())
        .body(PrincipalResponse.guest(result.userId(), result.username()));
  }

  /**
   * {@code rest-conventions.md}: "the frontend's tier check on load." Public (NFR-SEC-006): a
   * visitor with no valid session gets {@code 200 {"authenticated":false}}, not {@code 401}, so an
   * anonymous page load logs no console error.
   */
  @GetMapping("/session")
  ResponseEntity<?> session(HttpServletRequest request) {
    if (!(request.getUserPrincipal() instanceof AuthenticatedUser principal)) {
      return ResponseEntity.ok(ANONYMOUS);
    }
    return ResponseEntity.ok(
        accountRepository
            .findPrincipalById(principal.userId())
            .orElseThrow(() -> new UnauthenticatedException("This account no longer exists.")));
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
  private ResponseCookie sessionCookie(SessionService.IssuedSession session) {
    return sessionCookies.issue(session.token(), session.expiresAt());
  }

  private ResponseCookie clearedSessionCookie() {
    return sessionCookies.clear();
  }
}
