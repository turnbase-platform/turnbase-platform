package io.turnbase.platform.room;

import io.turnbase.platform.common.RoomUnavailableRetryException;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * {@code room-lifecycle.md} §4's real {@code detachSession} — replaces {@code
 * PendingRoomSessionDetacher} ({@code IMP-GATE-49}). Step 0's claim runs here; steps 1-6 are {@link
 * RoomRepository#detachSession}, run inside {@link RoomEventExecutor}'s lock and transaction so the
 * fenced commit and publish reuse the same machinery every other room mutation already uses; steps
 * 7-8 (broadcast, best-effort teardown) follow after the commit returns.
 */
@Component
class RoomSessionDetacherImpl implements RoomSessionDetacher {

  private final RoomOwnershipService ownership;
  private final RoomEventExecutor events;
  private final RoomRepository repository;
  private final SessionTerminator terminator;

  RoomSessionDetacherImpl(
      RoomOwnershipService ownership,
      RoomEventExecutor events,
      RoomRepository repository,
      SessionTerminator terminator) {
    this.ownership = ownership;
    this.events = events;
    this.repository = repository;
    this.terminator = terminator;
  }

  @Override
  public void detach(UUID roomId, @Nullable UUID sessionId, String reason, String nextState) {
    RoomOwnershipService.ClaimOutcome outcome = ownership.claim(roomId);
    boolean owned =
        outcome instanceof RoomOwnershipService.ClaimOutcome.AlreadyMine
            || outcome instanceof RoomOwnershipService.ClaimOutcome.Adopted;
    if (!owned) {
      // room-lifecycle.md §4 step 0 / flow 07: a caller that cannot claim defers through the
      // existing deferred-tail mechanism (§5.1 duty 2) rather than a table of its own (ADR-0010)
      // — the owning instance evaluates closure again on its own next tick.
      return;
    }
    RoomRepository.DetachOutcome result;
    try {
      result =
          events.execute(
              roomId,
              () -> {
                RoomRepository.DetachOutcome outcome2 =
                    repository.detachSession(roomId, sessionId, reason, nextState);
                RoomEventExecutor.RoomEvent event =
                    outcome2.detached() && "CLOSED".equals(nextState)
                        ? new RoomEventExecutor.RoomEvent(
                            "ROOM_CLOSED", new RoomRepository.RoomClosedPayload(reason))
                        : null;
                return new RoomEventExecutor.MutationOutcome<>(outcome2, event);
              });
    } catch (RoomUnavailableRetryException e) {
      // Ownership moved between this method's own claim and the transaction; the room's new
      // owner (or its own sweep) picks this evaluation up again on its own next tick.
      return;
    }
    if (result.detached() && sessionId != null) {
      terminator.terminateAndCleanup(sessionId, reason);
    }
  }
}
