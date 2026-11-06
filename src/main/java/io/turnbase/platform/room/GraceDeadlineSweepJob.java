package io.turnbase.platform.room;

import io.turnbase.platform.common.RoomUnavailableRetryException;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * {@code room-lifecycle.md} §5.1 — the 5s deadline sweep, run by {@link
 * GraceDeadlineSweepSchedulingConfig} on {@code turnbase.jobs.deadline-sweep.interval}. Ownership-
 * partitioned like {@code RoomOwnershipService}'s own lease renewal: every instance runs this tick,
 * each sweeping only the rooms {@code room.owner_instance_id} names as its own — no
 * {@code @SchedulerLock}, since a cluster-wide "exactly one instance" lock would leave every other
 * instance's owned rooms unswept.
 *
 * <p>The four duties run in this exact order every tick (§5.1's own ordering note: closing in duty
 * 2 discards that session's undelivered outbox rows, so draining first would dispatch a {@code
 * player_left} for a session about to be torn down).
 */
@Component
class GraceDeadlineSweepJob {

  /** No document names a batch size for the drain; a reasoned default, not a measurement. */
  private static final int OUTBOX_DRAIN_BATCH = 100;

  private final RoomOutboxRepository outboxRows;
  private final RoomPresenceRepository presence;
  private final RoomEventExecutor events;
  private final PodIdentity pod;
  private final RoomSessionDetacher detacher;
  private final GameInputOutbox outbox;

  GraceDeadlineSweepJob(
      RoomOutboxRepository outboxRows,
      RoomPresenceRepository presence,
      RoomEventExecutor events,
      PodIdentity pod,
      RoomSessionDetacher detacher,
      GameInputOutbox outbox) {
    this.outboxRows = outboxRows;
    this.presence = presence;
    this.events = events;
    this.pod = pod;
    this.detacher = detacher;
    this.outbox = outbox;
  }

  void run() {
    dueExpiries();
    deferredTails();
    elapsedMigrationWindows();
    drainOutbox();
  }

  // ---------------------------------------------------------------------
  // Duty 1 — due expiries (§3.2/§3.3).
  // ---------------------------------------------------------------------

  private void dueExpiries() {
    for (RoomRecords.DueReservation due : presence.dueGraceReservations(pod.id())) {
      resolveOne(due);
    }
  }

  private void resolveOne(RoomRecords.DueReservation due) {
    Optional<RoomRecords.PlayerLostPayload> lost;
    try {
      lost =
          events.execute(
              due.roomId(),
              () -> {
                Optional<RoomRecords.ResolvedReservation> resolved =
                    presence.resolveExpiredReservation(due);
                RoomRecords.PlayerLostPayload payload =
                    resolved
                        .map(
                            r ->
                                new RoomRecords.PlayerLostPayload(
                                    due.userId(), r.teamId(), "GRACE_PERIOD_EXPIRED"))
                        .orElse(null);
                RoomEventExecutor.RoomEvent event =
                    payload == null
                        ? null
                        : new RoomEventExecutor.RoomEvent("PLAYER_LOST", payload);
                return new RoomEventExecutor.MutationOutcome<>(Optional.ofNullable(payload), event);
              });
    } catch (RoomUnavailableRetryException e) {
      // Ownership moved since dueGraceReservations() ran; leave the row for whichever instance
      // owns it now — this tick's own query already filtered on ownership, so a fresh claim
      // elsewhere will pick this up on its own next tick.
      return;
    }
    if (lost.isPresent() && due.activeSessionId() != null) {
      // room-lifecycle.md §3.3: step 4's closure evaluation carries the same exemption as step
      // 2's outbox insert above (both keyed on active_session_id IS NOT NULL) — a Lobby room has
      // no session to detach, and §1.4 step 4 opens a reservation in every room state, so
      // evaluating closure here unconditionally would close every Lobby room on a rolling
      // deploy's mass adoption (FR-DC-007). An emptied Lobby room is instead collected by the
      // idle-Lobby duty (§5.2) under IDLE_TIMEOUT.
      evaluateClosureIfNoHumanRemains(due.roomId(), "GRACE_EXPIRED_NO_HUMAN");
    }
  }

  private void evaluateClosureIfNoHumanRemains(UUID roomId, String closedReason) {
    if (presence.anyHumanRemains(roomId)) {
      return;
    }
    UUID sessionId = presence.activeSessionId(roomId);
    detacher.detach(roomId, sessionId, closedReason, "CLOSED");
  }

  // ---------------------------------------------------------------------
  // Duty 2 — deferred tails: resolutions a foreign instance committed, still owed an announcement.
  // ---------------------------------------------------------------------

  private void deferredTails() {
    for (RoomRecords.DeferredTail tail : presence.deferredTails(pod.id())) {
      announceOne(tail);
    }
  }

  private void announceOne(RoomRecords.DeferredTail tail) {
    boolean announced;
    try {
      announced =
          events.execute(
              tail.roomId(),
              () -> {
                boolean won = presence.announceDeferredTail(tail.roomId(), tail.userId());
                RoomEventExecutor.RoomEvent event =
                    won
                        ? new RoomEventExecutor.RoomEvent(
                            "PLAYER_LOST",
                            new RoomRecords.PlayerLostPayload(
                                tail.userId(), tail.teamId(), "GRACE_PERIOD_EXPIRED"))
                        : null;
                return new RoomEventExecutor.MutationOutcome<>(won, event);
              });
    } catch (RoomUnavailableRetryException e) {
      return;
    }
    if (!announced) {
      return;
    }
    applyHostDispositionIfNeeded(tail);
    evaluateClosureIfNoHumanRemains(tail.roomId(), "NO_PLAYERS_REMAINING");
  }

  /**
   * §5.1 duty 2's host disposition: if the departed participant was the host, transfer to the next
   * connected human, if one exists. If none is connected but at least one human still remains (in
   * grace), {@link #evaluateClosureIfNoHumanRemains} below will not close the room and {@code
   * host_user_id} is left pointing at the departed user until someone reconnects — the same
   * "legitimately has no host" state `data-model.md` §1 already documents for the individual-grace
   * case; nulling it out here as well needs its own version-bumping room mutation with no event to
   * carry, which this item leaves as a known simplification rather than inventing a wire event no
   * document specifies.
   */
  private void applyHostDispositionIfNeeded(RoomRecords.DeferredTail tail) {
    if (!tail.userId().equals(tail.hostUserId())) {
      return;
    }
    Optional<UUID> nextHuman = presence.nextConnectedHumanExcluding(tail.roomId(), tail.userId());
    if (nextHuman.isEmpty()) {
      return;
    }
    try {
      events.execute(
          tail.roomId(),
          () -> {
            RoomRecords.HostTransferredPayload payload =
                presence.transferHostAutomatically(
                    tail.roomId(), nextHuman.get(), tail.userId(), "HOST_LEFT");
            return new RoomEventExecutor.MutationOutcome<>(
                payload, new RoomEventExecutor.RoomEvent("HOST_TRANSFERRED", payload));
          });
    } catch (RoomUnavailableRetryException e) {
      // Not this instance's problem any more.
    }
  }

  // ---------------------------------------------------------------------
  // Duty 3 — elapsed migration windows (FR-HDISC-004).
  // ---------------------------------------------------------------------

  private void elapsedMigrationWindows() {
    for (UUID roomId : presence.elapsedMigrationWindowRoomIds(pod.id())) {
      closeMigrationWindow(roomId);
    }
  }

  private void closeMigrationWindow(UUID roomId) {
    @Nullable UUID hostUserId = presence.hostUserIdOf(roomId);
    boolean hostConnected = hostUserId != null && presence.isConnected(roomId, hostUserId);
    Optional<UUID> nextHuman =
        hostConnected ? Optional.empty() : presence.nextConnectedHumanExcluding(roomId, hostUserId);
    try {
      events.execute(
          roomId,
          () -> {
            presence.clearMigrationWindow(roomId);
            if (nextHuman.isEmpty()) {
              return new RoomEventExecutor.MutationOutcome<>(Boolean.FALSE, null);
            }
            RoomRecords.HostTransferredPayload payload =
                presence.transferHostAutomatically(
                    roomId, nextHuman.get(), hostUserId, "HOST_DISCONNECTED");
            return new RoomEventExecutor.MutationOutcome<>(
                Boolean.TRUE, new RoomEventExecutor.RoomEvent("HOST_TRANSFERRED", payload));
          });
    } catch (RoomUnavailableRetryException e) {
      // Not this instance's room any more.
    }
  }

  // ---------------------------------------------------------------------
  // Duty 4 — outbox drain (data-model.md §4.2). Not ownership-partitioned: a gRPC call needs no
  // room claim, unlike the other three duties' STOMP broadcasts.
  // ---------------------------------------------------------------------

  private void drainOutbox() {
    for (RoomRecords.OutboxRow row : outboxRows.pendingOutboxRows(OUTBOX_DRAIN_BATCH)) {
      outbox.drainOne(row);
    }
  }
}
