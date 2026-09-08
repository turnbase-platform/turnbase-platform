package io.turnbase.platform.social;

import io.turnbase.platform.common.AuthenticatedUser;
import io.turnbase.platform.common.PageResponse;
import io.turnbase.platform.common.SortFieldValidator;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.security.Principal;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** {@code social-rest-api.md} §1's seven endpoints. */
@RestController
class SocialController {

  private static final Set<String> SEARCH_SORT_FIELDS = Set.of();
  private static final Set<String> PENDING_SORT_FIELDS = Set.of();
  private static final Set<String> FRIENDS_SORT_FIELDS = Set.of();

  private final SocialService socialService;
  private final SortFieldValidator sortFieldValidator;

  SocialController(SocialService socialService, SortFieldValidator sortFieldValidator) {
    this.socialService = socialService;
    this.sortFieldValidator = sortFieldValidator;
  }

  /** {@code social-rest-api.md} §2. */
  @GetMapping("/api/users")
  ResponseEntity<PageResponse<UserSearchResponse>> search(
      @RequestParam String search, Pageable pageable, HttpServletRequest request) {
    sortFieldValidator.validate(pageable.getSort(), SEARCH_SORT_FIELDS);
    AuthenticatedUser principal = principalOf(request);
    return ResponseEntity.ok(
        PageResponse.from(socialService.search(principal.userId(), search, pageable)));
  }

  /** {@code social-rest-api.md} §3. */
  @PostMapping("/api/friend-requests")
  ResponseEntity<SendFriendRequestResponse> sendRequest(
      @Valid @RequestBody SendFriendRequestRequest request, HttpServletRequest servletRequest) {
    AuthenticatedUser principal = principalOf(servletRequest);
    SendFriendRequestResponse response = socialService.sendRequest(principal.userId(), request);
    ResponseEntity.BodyBuilder status = ResponseEntity.status(HttpStatus.CREATED);
    if (response.friendRequest() != null) {
      status =
          status.location(
              URI.create("/api/friend-requests/" + response.friendRequest().requestId()));
    }
    return status.body(response);
  }

  /** {@code social-rest-api.md} §4. */
  @GetMapping("/api/friend-requests")
  ResponseEntity<PageResponse<PendingRequestResponse>> listPending(
      Pageable pageable, HttpServletRequest request) {
    sortFieldValidator.validate(pageable.getSort(), PENDING_SORT_FIELDS);
    AuthenticatedUser principal = principalOf(request);
    return ResponseEntity.ok(
        PageResponse.from(socialService.listPending(principal.userId(), pageable)));
  }

  /** {@code social-rest-api.md} §5. */
  @PostMapping("/api/friend-requests/{id}/accept")
  ResponseEntity<FriendSummaryResponse> accept(
      @PathVariable("id") UUID requestId, HttpServletRequest request) {
    AuthenticatedUser principal = principalOf(request);
    return ResponseEntity.ok(socialService.accept(principal.userId(), requestId));
  }

  /** {@code social-rest-api.md} §6. */
  @DeleteMapping("/api/friend-requests/{id}")
  ResponseEntity<Void> resolve(@PathVariable("id") UUID requestId, HttpServletRequest request) {
    AuthenticatedUser principal = principalOf(request);
    socialService.resolve(principal.userId(), requestId);
    return ResponseEntity.noContent().build();
  }

  /** {@code social-rest-api.md} §7 — public, privacy-resolved. */
  @GetMapping("/api/users/{username}/friends")
  ResponseEntity<PageResponse<FriendSummaryResponse>> friends(
      @PathVariable String username, Pageable pageable, HttpServletRequest request) {
    sortFieldValidator.validate(pageable.getSort(), FRIENDS_SORT_FIELDS);
    AuthenticatedUser viewer = optionalPrincipalOf(request);
    return ResponseEntity.ok(
        PageResponse.from(
            socialService.friendsOf(username, viewer == null ? null : viewer.userId(), pageable)));
  }

  /** {@code social-rest-api.md} §8. */
  @DeleteMapping("/api/users/me/friends/{userId}")
  ResponseEntity<Void> unfriend(@PathVariable UUID userId, HttpServletRequest request) {
    AuthenticatedUser principal = principalOf(request);
    socialService.unfriend(principal.userId(), userId);
    return ResponseEntity.noContent().build();
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
