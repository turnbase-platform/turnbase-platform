package io.turnbase.platform.room;

import io.turnbase.platform.room.RoomRecords.DeferredTail;
import io.turnbase.platform.room.RoomRecords.DetachOutcome;
import io.turnbase.platform.room.RoomRecords.DisconnectOutcome;
import io.turnbase.platform.room.RoomRecords.DueReservation;
import io.turnbase.platform.room.RoomRecords.HostTransferredPayload;
import io.turnbase.platform.room.RoomRecords.PlayerDisconnectedPayload;
import io.turnbase.platform.room.RoomRecords.PlayerLeftPayload;
import io.turnbase.platform.room.RoomRecords.PlayerReconnectedPayload;
import io.turnbase.platform.room.RoomRecords.ReconnectOutcome;
import io.turnbase.platform.room.RoomRecords.ResolvedReservation;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Disconnect, reconnect, the grace-deadline sweep and session detach. Split from the former {@code
 * RoomRepository} by {@code IMP-M20-14}; SQL and transactions are as they were.
 */
@Repository
class RoomPresenceRepository {

  private final JdbcClient jdbcClient;
  private final ApplicationEventPublisher events;
  private final RoomOutboxRepository outbox;
  private final RoomRepository rooms;

  RoomPresenceRepository(
      JdbcClient jdbcClient,
      ApplicationEventPublisher events,
      RoomOutboxRepository outbox,
      RoomRepository rooms) {
    this.jdbcClient = jdbcClient;
    this.events = events;
    this.outbox = outbox;
    this.rooms = rooms;
  }

  /**
   * Flow A steps 3-5: decrement, stop if still connected, else branch by room state and role.
   * Always runs on the owning instance (a STOMP session only exists after subscribe-time ownership
   * verification), so a Lobby/spectator departure announces inline and an In-Progress player
   * departure opens a reservation, both under {@link RoomEventExecutor}'s lock the caller already
   * holds.
   */
  DisconnectOutcome registerDisconnect(UUID roomId, UUID userId, Duration gracePeriod) {
    Optional<Integer> remaining =
        jdbcClient
            .sql(
                """
                update room_participant set connection_count = connection_count - 1
                 where room_id = :roomId and user_id = :userId
                   and left_at is null and connection_count > 0
                returning connection_count
                """)
            .param("roomId", roomId)
            .param("userId", userId)
            .query(Integer.class)
            .optional();
    if (remaining.isEmpty() || remaining.get() > 0) {
      // Empty: already left, or a duplicate disconnect event for an already-processed drop —
      // either way, nothing to do. > 0: another of this user's tabs is still open (FR-GRACE-006).
      return new DisconnectOutcome.StillConnected();
    }

    record RoomForDisconnect(String state, @Nullable UUID hostUserId) {}
    RoomForDisconnect room =
        jdbcClient
            .sql("select state, host_user_id from room where room_id = :roomId")
            .param("roomId", roomId)
            .query(
                (rs, n) ->
                    new RoomForDisconnect(
                        rs.getString("state"), rs.getObject("host_user_id", UUID.class)))
            .single();
    String role =
        jdbcClient
            .sql(
                "select role from room_participant"
                    + " where room_id = :roomId and user_id = :userId and left_at is null")
            .param("roomId", roomId)
            .param("userId", userId)
            .query(String.class)
            .single();

    if (!"IN_PROGRESS".equals(room.state()) || "SPECTATOR".equals(role)) {
      // FR-DC-002 (Lobby) / FR-DC-003 (spectator, any state): immediate leave, no reservation.
      return rooms
          .leaveRoom(userId, roomId, true)
          .<DisconnectOutcome>map(DisconnectOutcome.LeftImmediately::new)
          .orElse(new DisconnectOutcome.StillConnected());
    }

    // IN_PROGRESS, PLAYER: open a grace reservation (§3.1). Guarded on grace_reservation_id IS
    // NULL so a duplicate disconnect event (connection_count already decremented once but this
    // method re-entered before the first call's own transaction visibly committed) never resets
    // an already-running window.
    jdbcClient
        .sql(
            """
            update room_participant
               set grace_reservation_id = gen_random_uuid(),
                   grace_expires_at = now() + make_interval(secs => :graceSeconds)
             where room_id = :roomId and user_id = :userId
               and left_at is null and grace_reservation_id is null
            """)
        .param("roomId", roomId)
        .param("userId", userId)
        .param("graceSeconds", (int) gracePeriod.toSeconds())
        .update();
    boolean wasHost = userId.equals(room.hostUserId());
    return new DisconnectOutcome.GraceOpened(
        new PlayerDisconnectedPayload(userId, gracePeriod.toSeconds()), wasHost);
  }

  // ---------------------------------------------------------------------
  // Host transfer helpers, shared by disconnect (FR-HDISC-001), the migration-window sweep duty
  // (FR-HDISC-004) and the deferred-tail duty.
  // ---------------------------------------------------------------------

  /**
   * {@code room-lifecycle.md} §3.1's host-transfer note: skip while the migration window is open.
   */
  boolean migrationWindowOpen(UUID roomId) {
    Boolean open =
        jdbcClient
            .sql(
                "select migration_window_expires_at is not null and migration_window_expires_at > now()"
                    + " from room where room_id = :roomId")
            .param("roomId", roomId)
            .query(Boolean.class)
            .single();
    return Boolean.TRUE.equals(open);
  }

  /** FR-XFER-001's tie-break: the earliest-joined currently-connected human player, if any. */
  Optional<UUID> nextConnectedHumanExcluding(UUID roomId, @Nullable UUID excludeUserId) {
    return jdbcClient
        .sql(
            """
            select user_id from room_participant
             where room_id = :roomId and role = 'PLAYER' and left_at is null
               and connection_count > 0
               and (:excludeUserId::uuid is null or user_id <> :excludeUserId)
             order by join_order asc limit 1
            """)
        .param("roomId", roomId)
        .param("excludeUserId", excludeUserId)
        .query(UUID.class)
        .optional();
  }

  /**
   * {@code room-lifecycle.md} §3.2's reconnect mirror of {@link #registerDisconnect}'s decrement
   * (`IMP-M2-08`): a session subscribing to the room's topic for the first time
   * (`room.RoomSubscriptionAuthorizer`) establishes presence, and — guarded on an outstanding grace
   * reservation, exactly the statement §3.2 gives — resolves it atomically with the same increment.
   * Rowcount {@code 1} on the guarded update means this caller won the race and owns the {@code
   * PLAYER_RECONNECTED} broadcast; {@code 0} means there was nothing to resolve (an ordinary first
   * connection has no reservation at all), so a plain increment applies instead. Fenced — unlike
   * the old unconditional increment, this can produce a version-bumping event, so it must run
   * inside {@link RoomEventExecutor}'s lock like every other mutation that might publish one.
   */
  ReconnectOutcome resolveConnect(UUID roomId, UUID userId) {
    int reconnected =
        jdbcClient
            .sql(
                """
                update room_participant
                   set connection_count = connection_count + 1,
                       grace_reservation_id = null, grace_expires_at = null
                 where room_id = :roomId and user_id = :userId
                   and left_at is null and grace_reservation_id is not null
                """)
            .param("roomId", roomId)
            .param("userId", userId)
            .update();
    if (reconnected > 0) {
      return new ReconnectOutcome.Reconnected(new PlayerReconnectedPayload(userId));
    }
    jdbcClient
        .sql(
            "update room_participant set connection_count = connection_count + 1"
                + " where room_id = :roomId and user_id = :userId and left_at is null")
        .param("roomId", roomId)
        .param("userId", userId)
        .update();
    return new ReconnectOutcome.OrdinaryConnect();
  }

  /**
   * {@code room-websocket.md} Authorization Model, {@code IMP-GATE-51}: a {@code SUBSCRIBE} from a
   * non-member is a stranger to the room in the ordinary case (silent drop), but {@code
   * data-model.md} §2's row-reuse rule (`IMP-GATE-50`) means this caller's own history in the room
   * — if any — lives in exactly one row, so its {@code leave_reason} is inspectable. {@code
   * GRACE_EXPIRED} specifically means the caller lost the race to their own grace expiry, which is
   * told to the client (FR-REJOIN-007) rather than left to read as "room not found." Every other
   * terminal reason is an intentional past departure and stays a silent drop.
   */
  boolean lostRaceToOwnExpiry(UUID roomId, UUID userId) {
    Boolean lost =
        jdbcClient
            .sql(
                "select left_at is not null and leave_reason = 'GRACE_EXPIRED'"
                    + " from room_participant where room_id = :roomId and user_id = :userId")
            .param("roomId", roomId)
            .param("userId", userId)
            .query(Boolean.class)
            .optional()
            .orElse(false);
    return Boolean.TRUE.equals(lost);
  }

  boolean isConnected(UUID roomId, UUID userId) {
    Boolean connected =
        jdbcClient
            .sql(
                "select connection_count > 0 from room_participant"
                    + " where room_id = :roomId and user_id = :userId and left_at is null")
            .param("roomId", roomId)
            .param("userId", userId)
            .query(Boolean.class)
            .optional()
            .orElse(false);
    return Boolean.TRUE.equals(connected);
  }

  HostTransferredPayload transferHostAutomatically(
      UUID roomId, UUID newHostId, @Nullable UUID previousHostId, String reason) {
    jdbcClient
        .sql("update room set host_user_id = :newHostId where room_id = :roomId")
        .param("newHostId", newHostId)
        .param("roomId", roomId)
        .update();
    return new HostTransferredPayload(newHostId, previousHostId, reason);
  }

  void clearMigrationWindow(UUID roomId) {
    jdbcClient
        .sql("update room set migration_window_expires_at = null where room_id = :roomId")
        .param("roomId", roomId)
        .update();
  }

  // ---------------------------------------------------------------------
  // The deadline sweep (IMP-M2-07, room-lifecycle.md §5.1).
  // ---------------------------------------------------------------------

  /**
   * §5.1 duty 1's query: this instance's own due reservations. A closed room is excluded here
   * ({@code FR-DBQ-004}): {@code onRoomClosed} drops its epoch, so no duty can mutate it, and it
   * would otherwise be selected again on every tick until its purge. The rooms are chosen first,
   * and fenced with {@code materialized}: the grace index spans the reservations of closed rooms
   * too, and joining from it would read them all ({@code FR-DBQ-001}).
   */
  List<DueReservation> dueGraceReservations(String instanceId) {
    return jdbcClient
        .sql(
            """
            with mine as materialized (
              select room_id, active_session_id from room
               where owner_instance_id = :instanceId and state <> 'CLOSED'
            )
            select rp.room_id, rp.user_id, rp.grace_reservation_id, rp.team_id,
                   mine.active_session_id
              from mine join room_participant rp on rp.room_id = mine.room_id
             where rp.grace_expires_at <= now() and rp.left_at is null
            """)
        .param("instanceId", instanceId)
        .query(
            (rs, n) ->
                new DueReservation(
                    rs.getObject("room_id", UUID.class),
                    rs.getObject("user_id", UUID.class),
                    rs.getObject("grace_reservation_id", UUID.class),
                    rs.getObject("team_id", UUID.class),
                    rs.getObject("active_session_id", UUID.class)))
        .list();
  }

  /**
   * {@code room-lifecycle.md} §3.2's resolve statement, guarded for the expiry path (`:dueCheck =
   * true`) with {@code announceInline} always {@code true} — the sweep only ever resolves rows on
   * rooms it owns (duty 1's own query already filters on that). §3.3 step 2's conditional outbox
   * insert runs in the same transaction on a win.
   *
   * @return present iff this call's rowcount was 1 (this caller won and owns the side effects).
   */
  Optional<ResolvedReservation> resolveExpiredReservation(DueReservation due) {
    // A list, not .optional() on a single nullable column: RETURNING team_id can legitimately
    // return a row whose team_id is itself null, which .optional() cannot distinguish from "no
    // row matched" — the rowcount, not the value, is the entire idempotency story (§3.2).
    List<@Nullable UUID> rows =
        jdbcClient
            .sql(
                """
                update room_participant
                   set left_at = now(), leave_reason = 'GRACE_EXPIRED',
                       grace_reservation_id = null, grace_expires_at = null,
                       lost_announced_at = now()
                 where room_id = :roomId and user_id = :userId
                   and grace_reservation_id = :reservationId
                   and left_at is null and grace_expires_at <= now()
                returning team_id
                """)
            .param("roomId", due.roomId())
            .param("userId", due.userId())
            .param("reservationId", due.reservationId())
            .query(UUID.class)
            .list();
    if (rows.isEmpty()) {
      return Optional.empty();
    }
    UUID teamId = rows.get(0);
    if (due.activeSessionId() != null) {
      // Left to the sweep's own duty 4 (§5.1's ordering note): duty 2 may close this room in the
      // same tick and discard the row, which an inline dispatch would have already delivered.
      outbox.insertPlayerLeftOutbox(
          due.roomId(), due.userId(), teamId, "grace_period_expired", false);
    }
    return Optional.of(new ResolvedReservation(teamId));
  }

  /**
   * §3.3 step 4 / §5.1 duty 2's shared closure predicate: no participant remains with {@code
   * left_at IS NULL AND role = 'PLAYER'} — a participant still in grace counts as present.
   */
  boolean anyHumanRemains(UUID roomId) {
    Boolean exists =
        jdbcClient
            .sql(
                "select exists(select 1 from room_participant"
                    + " where room_id = :roomId and role = 'PLAYER' and left_at is null)")
            .param("roomId", roomId)
            .query(Boolean.class)
            .single();
    return Boolean.TRUE.equals(exists);
  }

  @Nullable UUID activeSessionId(UUID roomId) {
    return jdbcClient
        .sql("select active_session_id from room where room_id = :roomId")
        .param("roomId", roomId)
        .query(UUID.class)
        .optional()
        .orElse(null);
  }

  // ---------------------------------------------------------------------
  // Detaching a session (room-lifecycle.md §4, IMP-M2-09, IMP-GATE-49) — one method, five
  // callers; this repository method is steps 1-6, always invoked from inside RoomEventExecutor's
  // lock and transaction, matching resolveExpiredReservation's own precedent of relying on that
  // wrapping transaction rather than declaring @Transactional itself. Step 3's version bump and
  // epoch fence are RoomEventExecutor's own follow-up statement, the same split changeParameters
  // and transferHost already use for a room-column mutation with no CAS of its own — the shared
  // transaction rolls both back together if the fenced commit loses the race.
  // ---------------------------------------------------------------------

  /**
   * @param sessionId the session the caller believes is still attached; {@code null} for a room
   *     with none (a Lobby host-leave). Step 2's comparison against the row's actual {@code
   *     active_session_id} — null-safe, so two nulls count as a match — is the whole idempotency
   *     story: a second caller racing to detach the same session, or one arriving after another
   *     already did, is a safe no-op.
   */
  DetachOutcome detachSession(
      UUID roomId, @Nullable UUID sessionId, String reason, String nextState) {
    // A list, not .optional() on a single nullable column, for the same reason
    // resolveExpiredReservation is: a Lobby room's active_session_id is legitimately null, which
    // .optional() cannot distinguish from "no row matched" (the room actually vanished).
    List<@Nullable UUID> rows =
        jdbcClient
            .sql("select active_session_id from room where room_id = :roomId for update")
            .param("roomId", roomId)
            .query(UUID.class)
            .list();
    if (rows.isEmpty()) {
      throw new IllegalStateException("room " + roomId + " vanished");
    }
    UUID currentSessionId = rows.get(0);
    if (!java.util.Objects.equals(currentSessionId, sessionId)) {
      return new DetachOutcome(false);
    }
    jdbcClient
        .sql(
            """
            update room
               set state = :nextState, active_session_id = null,
                   closed_at = case when :nextState = 'CLOSED' then now() end,
                   closed_reason = case when :nextState = 'CLOSED' then :reason end
             where room_id = :roomId
            """)
        .param("nextState", nextState)
        .param("reason", reason)
        .param("roomId", roomId)
        .update();
    if ("CLOSED".equals(nextState)) {
      // FR-INV-008: deleted at closure, not left to the room_invitation_room_id_fkey cascade,
      // which only fires seven days later at FR-JOB-009's purge (data-model.md §1 Closure
      // reasons).
      jdbcClient
          .sql("delete from room_invitation where room_id = :roomId")
          .param("roomId", roomId)
          .update();
      afterCloseCommitted(roomId);
    }
    if (sessionId != null) {
      // ADR-0010: the owed teardown is a row keyed by sessionId, never a room-level flag, and any
      // undelivered game input for that session is now moot (data-model.md §4.2).
      jdbcClient
          .sql(
              "insert into session_teardown (session_id, room_id, reason)"
                  + " values (:sessionId, :roomId, :reason) on conflict (session_id) do nothing")
          .param("sessionId", sessionId)
          .param("roomId", roomId)
          .param("reason", reason)
          .update();
      jdbcClient
          .sql(
              "delete from game_input_outbox"
                  + " where session_id = :sessionId and dispatched_at is null")
          .param("sessionId", sessionId)
          .update();
    }
    return new DetachOutcome(true);
  }

  /**
   * Runs once the closing transaction has committed, so a rollback leaves the room owned. Without a
   * transaction (unit tests call {@link #detachSession} bare, and {@code registerSynchronization}
   * throws then) it runs immediately.
   */
  private void afterCloseCommitted(UUID roomId) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      events.publishEvent(new RoomClosedEvent(roomId));
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            events.publishEvent(new RoomClosedEvent(roomId));
          }
        });
  }

  /**
   * FR-EXPIRY-006: a game end returns the room to Lobby, where a reservation means nothing, so
   * every reservation still outstanding is resolved as a departure — announced already (the {@code
   * GAME_ENDED} that follows is the announcement) and with no engine notification, the session
   * being over.
   */
  void resolveOutstandingReservations(UUID roomId) {
    jdbcClient
        .sql(
            """
            update room_participant
               set left_at = now(), leave_reason = 'GRACE_EXPIRED', lost_announced_at = now(),
                   grace_reservation_id = null, grace_expires_at = null
             where room_id = :roomId and left_at is null and grace_reservation_id is not null
            """)
        .param("roomId", roomId)
        .update();
  }

  /**
   * FR-JOB-007c / {@code room-lifecycle.md} §5.2: "clean keys, reset readiness, notify
   * participants" — the readiness half, applied to every current participant of the room, not just
   * players (a spectator has no {@code ready} value that means anything, but resetting it is
   * harmless and avoids a role-conditional statement for no observable difference).
   */
  void resetReadiness(UUID roomId) {
    jdbcClient
        .sql(
            "update room_participant set ready = false where room_id = :roomId and left_at is null")
        .param("roomId", roomId)
        .update();
  }

  /**
   * §5.1 duty 2's query: departures a foreign instance resolved, still owed an announcement. Not
   * for a closed room ({@code FR-DBQ-004}): nobody listens to its topic and its closure is done.
   * Rooms first, as for {@link #dueGraceReservations}: the unannounced index spans closed rooms.
   */
  List<DeferredTail> deferredTails(String instanceId) {
    return jdbcClient
        .sql(
            """
            with mine as materialized (
              select room_id, host_user_id from room
               where owner_instance_id = :instanceId and state <> 'CLOSED'
            )
            select rp.room_id, rp.user_id, rp.team_id, rp.leave_reason, mine.host_user_id
              from mine join room_participant rp on rp.room_id = mine.room_id
             where rp.left_at is not null and rp.lost_announced_at is null
               and rp.leave_reason in ('GRACE_EXPIRED', 'EARLY_RELEASE', 'LEFT')
            """)
        .param("instanceId", instanceId)
        .query(
            (rs, n) ->
                new DeferredTail(
                    rs.getObject("room_id", UUID.class),
                    rs.getObject("user_id", UUID.class),
                    rs.getObject("team_id", UUID.class),
                    rs.getString("leave_reason"),
                    rs.getObject("host_user_id", UUID.class)))
        .list();
  }

  /**
   * §5.1 duty 2's announcement half: stamp {@code lost_announced_at}, guarded so a row another
   * concurrent tick already announced is a no-op.
   */
  boolean announceDeferredTail(UUID roomId, UUID userId) {
    int updated =
        jdbcClient
            .sql(
                "update room_participant set lost_announced_at = now()"
                    + " where room_id = :roomId and user_id = :userId and lost_announced_at is null")
            .param("roomId", roomId)
            .param("userId", userId)
            .update();
    return updated > 0;
  }

  /**
   * §5.1 duty 3's query: elapsed migration windows on rooms this instance owns and can still
   * change. A room that closed inside its window keeps {@code owner_instance_id} and the window, so
   * {@code state <> 'CLOSED'} is what stops it being re-read every tick ({@code FR-DBQ-004}).
   */
  List<UUID> elapsedMigrationWindowRoomIds(String instanceId) {
    return jdbcClient
        .sql(
            "select room_id from room"
                + " where migration_window_expires_at <= now() and owner_instance_id = :instanceId"
                + " and state <> 'CLOSED'")
        .param("instanceId", instanceId)
        .query(UUID.class)
        .list();
  }

  /**
   * Stamps {@code lost_announced_at} on a participant {@link #releaseCallersCurrentRoom} just
   * released, so the deferred-tail duty skips it, and returns the {@code PLAYER_LEFT} payload the
   * caller now owes. Empty when another actor already announced it.
   */
  Optional<PlayerLeftPayload> announceReleasedDeparture(UUID roomId, UUID userId) {
    return jdbcClient
        .sql(
            """
            update room_participant set lost_announced_at = now()
             where room_id = :roomId and user_id = :userId
               and left_at is not null and lost_announced_at is null
            returning role
            """)
        .param("roomId", roomId)
        .param("userId", userId)
        .query(String.class)
        .optional()
        .map(role -> new PlayerLeftPayload(userId, role));
  }

  /** FR-XFER-001: the next human player by join order, preferring one with a live connection. */
  Optional<UUID> nextHumanPlayer(UUID roomId) {
    return jdbcClient
        .sql(
            """
            select user_id from room_participant
             where room_id = :roomId and role = 'PLAYER' and left_at is null
             order by (connection_count > 0) desc, join_order asc limit 1
            """)
        .param("roomId", roomId)
        .query(UUID.class)
        .optional();
  }

  @Nullable UUID hostUserIdOf(UUID roomId) {
    return jdbcClient
        .sql("select host_user_id from room where room_id = :roomId")
        .param("roomId", roomId)
        .query(UUID.class)
        .optional()
        .orElse(null);
  }
}
