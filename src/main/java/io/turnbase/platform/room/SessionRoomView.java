package io.turnbase.platform.room;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * What the move path needs to know about the room whose active session this is: the room's state
 * and its current (not left) participants. Read from Postgres in one statement and held by {@link
 * ParticipantCache}.
 */
record SessionRoomView(UUID roomId, UUID sessionId, String state, List<Member> members) {

  /** A current participant; {@code teamId} is null for a spectator. */
  record Member(UUID userId, String role, @Nullable UUID teamId, String username) {}

  /**
   * The acting player of {@code SUBMIT_ACTION}: a current participant of a room that is {@code
   * IN_PROGRESS}. The same answer {@code RoomOutboxRepository#actingParticipant} gives.
   */
  Optional<RoomRecords.ActingParticipant> actingParticipant(UUID userId) {
    if (!"IN_PROGRESS".equals(state)) {
      return Optional.empty();
    }
    return members.stream()
        .filter(m -> m.userId().equals(userId))
        .findFirst()
        .map(m -> new RoomRecords.ActingParticipant(m.role(), m.teamId(), m.username()));
  }
}
