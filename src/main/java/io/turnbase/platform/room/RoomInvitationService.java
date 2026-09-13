package io.turnbase.platform.room;

import io.turnbase.platform.common.Canonicalizer;
import io.turnbase.platform.common.RetentionProperties;
import io.turnbase.platform.common.UsernameSkeleton;
import io.turnbase.platform.notification.AlertPublisher;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * {@code room-invitation-rest-api.md} §§2-3 — send and decline. Acceptance is {@link
 * RoomService#join}.
 */
@Service
class RoomInvitationService {

  private static final String TYPE_INVITATION_DECLINED = "INVITATION_DECLINED";

  private final RoomInvitationRepository repository;
  private final RetentionProperties retentionProperties;
  private final AlertPublisher alertPublisher;

  RoomInvitationService(
      RoomInvitationRepository repository,
      RetentionProperties retentionProperties,
      AlertPublisher alertPublisher) {
    this.repository = repository;
    this.retentionProperties = retentionProperties;
    this.alertPublisher = alertPublisher;
  }

  /** {@code room-invitation-rest-api.md} §2. */
  RoomInvitationResponse send(UUID hostUserId, UUID roomId, SendInvitationRequest request) {
    String skeleton = UsernameSkeleton.of(Canonicalizer.canonicalize(request.inviteeUsername()));
    RoomInvitationRepository.SendResult result =
        repository.send(hostUserId, roomId, skeleton, retentionProperties.notification());
    return new RoomInvitationResponse(
        result.invitationId(), result.inviteeUsername(), result.createdAt(), result.expiresAt());
  }

  /**
   * {@code room-invitation-rest-api.md} §3. {@code 204} whether or not a matching invitation
   * existed; the alert fires only when one actually did.
   */
  void decline(UUID roomId, UUID inviteeUserId) {
    repository
        .decline(roomId, inviteeUserId)
        .ifPresent(
            resolved ->
                alertPublisher.publish(
                    resolved.inviterUserId(),
                    TYPE_INVITATION_DECLINED,
                    Map.of("user", resolved.inviteeUsername(), "roomId", roomId.toString())));
  }
}
