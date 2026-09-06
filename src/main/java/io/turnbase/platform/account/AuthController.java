package io.turnbase.platform.account;

import io.turnbase.platform.common.SessionCookie;
import io.turnbase.platform.session.SessionService;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code rest-conventions.md} § Authentication, {@code flows/01-arrival-and-account.md} §D. Both
 * endpoints are exempt from authentication per NFR-SEC-006.
 */
@RestController
@RequestMapping("/api/auth")
class AuthController {

  private final RegistrationService registrationService;
  private final LoginService loginService;

  AuthController(RegistrationService registrationService, LoginService loginService) {
    this.registrationService = registrationService;
    this.loginService = loginService;
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
}
