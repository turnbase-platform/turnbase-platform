package io.turnbase.platform.room;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The read {@code chat} needs from {@code room} — a deliberate cross-feature API rather than {@code
 * chat} reaching into {@code room}'s internals (ADR-0014 §3), even though the wire destinations
 * chat governs are namespaced under {@code room.*}. {@code room-chat-websocket.md}: "Team
 * assignment is re-validated inside the handler under the room lock, matching the authoritative
 * handler-level check pattern" — {@link #resolveSenderForSend} is that check; {@link
 * #currentMembership} is the lighter, unlocked read chat's {@code SUBSCRIBE} authorizer uses,
 * mirroring {@code room}'s own bare-topic subscribe check.
 */
public interface RoomChatPort {

  /** Unlocked — for {@code SUBSCRIBE} authorization on the chat/team sub-topics. */
  Optional<ChatMembership> currentMembership(UUID roomId, UUID userId);

  /**
   * Lock-serialized against every other room command ({@code RoomEventExecutor}'s own lock) so a
   * concurrent {@code REASSIGN_PLAYER} cannot race a chat send to a stale team, and so {@code
   * serverTimestamp} is assigned monotonically within a room. Throws {@code
   * io.turnbase.platform.common.RoomUnavailableRetryException} if this instance does not currently
   * own the room, the same failure every other STOMP command handler surfaces.
   */
  Optional<ChatSenderContext> resolveSenderForSend(UUID roomId, UUID userId);

  record ChatMembership(String role, @Nullable UUID teamId) {}

  record ChatSenderContext(
      String role,
      @Nullable UUID teamId,
      String username,
      boolean teamChatAvailable,
      Instant serverTimestamp) {}
}
