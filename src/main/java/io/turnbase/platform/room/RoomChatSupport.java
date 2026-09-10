package io.turnbase.platform.room;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * {@link RoomChatPort}'s only implementation, backed by {@link RoomRepository}/{@link
 * RoomEventExecutor}.
 */
@Component
class RoomChatSupport implements RoomChatPort {

  private final RoomRepository repository;
  private final RoomEventExecutor events;

  RoomChatSupport(RoomRepository repository, RoomEventExecutor events) {
    this.repository = repository;
    this.events = events;
  }

  @Override
  public Optional<ChatMembership> currentMembership(UUID roomId, UUID userId) {
    return repository
        .currentParticipantForChat(roomId, userId)
        .map(row -> new ChatMembership(row.role(), row.teamId()));
  }

  @Override
  public Optional<ChatSenderContext> resolveSenderForSend(UUID roomId, UUID userId) {
    return events.withLock(
        roomId,
        () -> {
          Instant now = Instant.now();
          return repository
              .currentParticipantForChat(roomId, userId)
              .map(
                  row ->
                      new ChatSenderContext(
                          row.role(), row.teamId(), row.username(), row.teamChatAvailable(), now));
        });
  }
}
