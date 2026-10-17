package io.turnbase.platform.room;

import io.turnbase.platform.common.AuthenticatedUser;
import io.turnbase.platform.websocket.SubscriptionAuthorizer;
import io.turnbase.platform.websocket.SubscriptionDecision;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * {@code /topic/room.{roomId}.start} ({@code room-websocket.md} § Authorization Model): any current
 * member or spectator of the room, a silent drop for everyone else. Unlike the bare room topic it
 * claims no ownership and counts no connection — it is a read-only side channel, and the room topic
 * the same client has already subscribed to carries those duties.
 */
@Component
class RoomStartStatusAuthorizer implements SubscriptionAuthorizer {

  private static final String PREFIX = "/topic/room.";
  private static final String SUFFIX = ".start";

  private final RoomRepository repository;

  RoomStartStatusAuthorizer(RoomRepository repository) {
    this.repository = repository;
  }

  @Override
  public boolean supports(String destination) {
    return destination.startsWith(PREFIX) && destination.endsWith(SUFFIX);
  }

  @Override
  public SubscriptionDecision authorize(
      AuthenticatedUser user, String destination, String stompSessionId) {
    UUID roomId;
    try {
      roomId =
          UUID.fromString(
              destination.substring(PREFIX.length(), destination.length() - SUFFIX.length()));
    } catch (IllegalArgumentException | StringIndexOutOfBoundsException e) {
      return new SubscriptionDecision.SilentlyDrop();
    }
    return repository.isCurrentParticipant(roomId, user.userId())
        ? new SubscriptionDecision.Allow()
        : new SubscriptionDecision.SilentlyDrop();
  }
}
