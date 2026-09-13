package io.turnbase.platform.room;

import io.turnbase.platform.common.AuthenticatedUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.security.Principal;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** {@code room-invitation-rest-api.md} §1's two endpoints. */
@RestController
class RoomInvitationController {

  private final RoomInvitationService invitationService;

  RoomInvitationController(RoomInvitationService invitationService) {
    this.invitationService = invitationService;
  }

  /** {@code room-invitation-rest-api.md} §2. */
  @PostMapping("/api/rooms/{roomId}/invitations")
  ResponseEntity<RoomInvitationResponse> send(
      @PathVariable UUID roomId,
      @Valid @RequestBody SendInvitationRequest request,
      HttpServletRequest servletRequest) {
    AuthenticatedUser principal = principalOf(servletRequest);
    RoomInvitationResponse response = invitationService.send(principal.userId(), roomId, request);
    return ResponseEntity.status(HttpStatus.CREATED).body(response);
  }

  /** {@code room-invitation-rest-api.md} §3. */
  @DeleteMapping("/api/rooms/{roomId}/invitations/me")
  ResponseEntity<Void> decline(@PathVariable UUID roomId, HttpServletRequest servletRequest) {
    AuthenticatedUser principal = principalOf(servletRequest);
    invitationService.decline(roomId, principal.userId());
    return ResponseEntity.noContent().build();
  }

  private static AuthenticatedUser principalOf(HttpServletRequest request) {
    Principal principal = request.getUserPrincipal();
    if (principal instanceof AuthenticatedUser authenticatedUser) {
      return authenticatedUser;
    }
    throw new IllegalStateException(
        "SessionAuthenticationInterceptor must reject an unauthenticated request to this endpoint"
            + " before its handler runs");
  }
}
