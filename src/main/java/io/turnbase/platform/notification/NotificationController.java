package io.turnbase.platform.notification;

import io.turnbase.platform.common.AuthenticatedUser;
import io.turnbase.platform.common.PageResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.security.Principal;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code notification-rest-api.md} §1's five endpoints. The stream is {@link
 * NotificationStreamController}.
 */
@RestController
class NotificationController {

  private final NotificationService notificationService;

  NotificationController(NotificationService notificationService) {
    this.notificationService = notificationService;
  }

  /** §2. Fixed order only — no filter or sort query params are exposed. */
  @GetMapping("/api/notifications")
  ResponseEntity<PageResponse<NotificationResponse>> list(
      Pageable pageable, HttpServletRequest servletRequest) {
    AuthenticatedUser principal = principalOf(servletRequest);
    return ResponseEntity.ok(notificationService.list(principal.userId(), pageable));
  }

  /** §3. */
  @GetMapping("/api/notifications/unread-count")
  ResponseEntity<UnreadCountResponse> unreadCount(HttpServletRequest servletRequest) {
    AuthenticatedUser principal = principalOf(servletRequest);
    return ResponseEntity.ok(
        new UnreadCountResponse(notificationService.unreadCount(principal.userId())));
  }

  /** §4. */
  @PatchMapping("/api/notifications/{id}")
  ResponseEntity<NotificationResponse> markRead(
      @PathVariable UUID id,
      @Valid @RequestBody MarkReadRequest request,
      HttpServletRequest servletRequest) {
    AuthenticatedUser principal = principalOf(servletRequest);
    return ResponseEntity.ok(notificationService.markRead(id, principal.userId(), request));
  }

  /** §5. */
  @PostMapping("/api/notifications/read-all")
  ResponseEntity<Void> markAllRead(HttpServletRequest servletRequest) {
    AuthenticatedUser principal = principalOf(servletRequest);
    notificationService.markAllRead(principal.userId());
    return ResponseEntity.noContent().build();
  }

  /** §6. */
  @DeleteMapping("/api/notifications/{id}")
  ResponseEntity<Void> delete(@PathVariable UUID id, HttpServletRequest servletRequest) {
    AuthenticatedUser principal = principalOf(servletRequest);
    notificationService.delete(id, principal.userId());
    return ResponseEntity.noContent().build();
  }

  static AuthenticatedUser principalOf(HttpServletRequest request) {
    Principal principal = request.getUserPrincipal();
    if (principal instanceof AuthenticatedUser authenticatedUser) {
      return authenticatedUser;
    }
    throw new IllegalStateException(
        "SessionAuthenticationInterceptor must reject an unauthenticated request to this endpoint"
            + " before its handler runs");
  }
}
