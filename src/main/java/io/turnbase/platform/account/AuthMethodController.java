package io.turnbase.platform.account;

import io.turnbase.platform.common.AuthenticatedUser;
import io.turnbase.platform.common.OAuthLinkCookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code rest-conventions.md} § Authentication — the three {@code /api/users/me/auth-methods}
 * routes (IMP-GATE-78). All session-required: the principal is the resource, so no user id is ever
 * accepted in the path.
 */
@RestController
@RequestMapping("/api/users/me/auth-methods")
class AuthMethodController {

  private final AuthMethodService authMethodService;

  AuthMethodController(AuthMethodService authMethodService) {
    this.authMethodService = authMethodService;
  }

  @PostMapping("/password")
  ResponseEntity<Void> addPassword(
      @Valid @RequestBody AddPasswordRequest request, HttpServletRequest servletRequest) {
    authMethodService.addPassword(principalOf(servletRequest).userId(), request);
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/google")
  ResponseEntity<AuthMethodsResponse> confirmGoogleLink(
      @CookieValue(name = OAuthLinkCookie.NAME, required = false) @Nullable String linkToken,
      HttpServletRequest servletRequest) {
    AuthMethodsResponse body =
        new AuthMethodsResponse(
            authMethodService.confirmGoogleLink(principalOf(servletRequest).userId(), linkToken));
    return ResponseEntity.ok()
        .header(HttpHeaders.SET_COOKIE, OAuthController.clearedLinkCookie().toString())
        .body(body);
  }

  @DeleteMapping("/{method}")
  ResponseEntity<Void> remove(@PathVariable String method, HttpServletRequest servletRequest) {
    authMethodService.remove(principalOf(servletRequest).userId(), method);
    return ResponseEntity.noContent().build();
  }

  private static AuthenticatedUser principalOf(HttpServletRequest request) {
    if (request.getUserPrincipal() instanceof AuthenticatedUser principal) {
      return principal;
    }
    throw new IllegalStateException(
        "SessionAuthenticationInterceptor must reject an unauthenticated request to this"
            + " endpoint before its handler runs");
  }
}
