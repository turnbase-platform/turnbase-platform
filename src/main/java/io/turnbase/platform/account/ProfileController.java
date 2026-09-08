package io.turnbase.platform.account;

import io.turnbase.platform.common.AuthenticatedUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.security.Principal;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code rest-conventions.md} § Authentication's two self-scoped account routes plus the public
 * profile read. {@code GET /{username}} is exempt from session authentication ({@code
 * user-accounts.md} NFR-SEC-006, {@code SessionAuthenticationInterceptor}); the {@code /me/**}
 * mutations are not — {@code AuthenticatedUser} is guaranteed non-null there before the handler
 * runs.
 */
@RestController
@RequestMapping("/api/users")
class ProfileController {

  private final ProfileService profileService;

  ProfileController(ProfileService profileService) {
    this.profileService = profileService;
  }

  @GetMapping("/{username}")
  ResponseEntity<ProfileResponse> getProfile(
      @PathVariable String username, HttpServletRequest request) {
    AuthenticatedUser viewer = optionalPrincipalOf(request);
    ProfileResponse response =
        profileService.view(username, viewer == null ? null : viewer.userId());
    return ResponseEntity.ok(response);
  }

  @PatchMapping("/me")
  ResponseEntity<IdentityResponse> updateIdentity(
      @Valid @RequestBody UpdateProfileRequest request, HttpServletRequest servletRequest) {
    AuthenticatedUser principal = principalOf(servletRequest);
    return ResponseEntity.ok(profileService.updateIdentity(principal.userId(), request));
  }

  @PostMapping("/me/password")
  ResponseEntity<Void> changePassword(
      @Valid @RequestBody ChangePasswordRequest request, HttpServletRequest servletRequest) {
    AuthenticatedUser principal = principalOf(servletRequest);
    profileService.changePassword(principal.userId(), request);
    return ResponseEntity.noContent().build();
  }

  @PatchMapping("/me/privacy")
  ResponseEntity<PrivacyResponse> updatePrivacy(
      @Valid @RequestBody UpdatePrivacyRequest request, HttpServletRequest servletRequest) {
    AuthenticatedUser principal = principalOf(servletRequest);
    return ResponseEntity.ok(profileService.updatePrivacySettings(principal.userId(), request));
  }

  private static AuthenticatedUser principalOf(HttpServletRequest request) {
    AuthenticatedUser principal = optionalPrincipalOf(request);
    if (principal == null) {
      throw new IllegalStateException(
          "SessionAuthenticationInterceptor must reject an unauthenticated request to this"
              + " endpoint before its handler runs");
    }
    return principal;
  }

  private static @Nullable AuthenticatedUser optionalPrincipalOf(HttpServletRequest request) {
    Principal principal = request.getUserPrincipal();
    return principal instanceof AuthenticatedUser authenticatedUser ? authenticatedUser : null;
  }
}
