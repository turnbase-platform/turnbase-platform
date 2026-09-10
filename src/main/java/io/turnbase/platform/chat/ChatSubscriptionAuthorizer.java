package io.turnbase.platform.chat;

import io.turnbase.platform.common.AuthenticatedUser;
import io.turnbase.platform.room.RoomChatPort;
import io.turnbase.platform.websocket.SubscriptionAuthorizer;
import io.turnbase.platform.websocket.SubscriptionDecision;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * {@code room-chat-websocket.md} § Authorization — the chat/team sub-topics {@code
 * room.RoomSubscriptionAuthorizer} deliberately excludes ({@code IMP-M2-02}'s own comment names
 * this item as their owner). Both rows in that table resolve to a silent drop on failure; unlike
 * the bare room topic, chat never rejects a subscription with {@code ROOM_UNAVAILABLE_RETRY} — the
 * table names no such outcome, and {@link RoomChatPort#currentMembership} is a plain, unlocked read
 * (mirroring {@code room}'s own bare-topic membership check), not the lock-serialized,
 * ownership-checked one {@code SEND} uses.
 */
@Component
class ChatSubscriptionAuthorizer implements SubscriptionAuthorizer {

  private static final String ROOM_TOPIC_PREFIX = "/topic/room.";

  private final RoomChatPort roomChatPort;

  ChatSubscriptionAuthorizer(RoomChatPort roomChatPort) {
    this.roomChatPort = roomChatPort;
  }

  @Override
  public boolean supports(String destination) {
    return parse(destination) != null;
  }

  @Override
  public SubscriptionDecision authorize(
      AuthenticatedUser user, String destination, String stompSessionId) {
    Parsed parsed = parse(destination);
    if (parsed == null) {
      return new SubscriptionDecision.SilentlyDrop();
    }
    Optional<RoomChatPort.ChatMembership> membership =
        roomChatPort.currentMembership(parsed.roomId(), user.userId());
    if (membership.isEmpty() || !"PLAYER".equals(membership.get().role())) {
      return new SubscriptionDecision.SilentlyDrop();
    }
    if (parsed.teamId() != null && !parsed.teamId().equals(membership.get().teamId())) {
      return new SubscriptionDecision.SilentlyDrop();
    }
    return new SubscriptionDecision.Allow();
  }

  /** {@code {roomId}.chat} or {@code {roomId}.team.{teamId}}; {@code null} if malformed. */
  private static @Nullable Parsed parse(String destination) {
    if (!destination.startsWith(ROOM_TOPIC_PREFIX)) {
      return null;
    }
    String[] parts = destination.substring(ROOM_TOPIC_PREFIX.length()).split("\\.");
    try {
      if (parts.length == 2 && "chat".equals(parts[1])) {
        return new Parsed(UUID.fromString(parts[0]), null);
      }
      if (parts.length == 3 && "team".equals(parts[1])) {
        return new Parsed(UUID.fromString(parts[0]), UUID.fromString(parts[2]));
      }
    } catch (IllegalArgumentException e) {
      return null;
    }
    return null;
  }

  private record Parsed(UUID roomId, @Nullable UUID teamId) {}
}
