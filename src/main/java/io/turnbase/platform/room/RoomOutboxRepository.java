package io.turnbase.platform.room;

import io.turnbase.platform.room.RoomRecords.ActingParticipant;
import io.turnbase.platform.room.RoomRecords.DueTeardown;
import io.turnbase.platform.room.RoomRecords.OutboxRow;
import io.turnbase.platform.room.RoomRecords.PlayerLeftQueued;
import io.turnbase.platform.room.RoomRecords.RoomSessionPair;
import io.turnbase.platform.room.RoomRecords.SessionParticipant;
import io.turnbase.platform.room.RoomRecords.SessionRoomRow;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/**
 * The game-input outbox, session-side reads and the reconciliation queries. Split from the former
 * {@code RoomRepository} by {@code IMP-M20-14}; SQL and transactions are as they were.
 */
@Repository
class RoomOutboxRepository {

  private final JdbcClient jdbcClient;
  private final JsonMapper objectMapper;
  private final ApplicationEventPublisher events;
  private final RoomReadRepository reads;

  RoomOutboxRepository(
      JdbcClient jdbcClient,
      JsonMapper objectMapper,
      ApplicationEventPublisher events,
      RoomReadRepository reads) {
    this.jdbcClient = jdbcClient;
    this.objectMapper = objectMapper;
    this.events = events;
    this.reads = reads;
  }

  List<OutboxRow> pendingOutboxRows(int limit) {
    return jdbcClient
        .sql(
            """
            select id, session_id, sequence, payload::text as payload
              from game_input_outbox
             where dispatched_at is null
             order by id
             limit :limit
             for update skip locked
            """)
        .param("limit", limit)
        .query(
            (rs, n) ->
                new OutboxRow(
                    rs.getLong("id"),
                    rs.getObject("session_id", UUID.class),
                    rs.getObject("sequence") == null ? null : rs.getLong("sequence"),
                    rs.getString("payload")))
        .list();
  }

  /** The session's undelivered rows, oldest first — what an inline attempt after commit drains. */
  List<OutboxRow> pendingOutboxRows(UUID sessionId) {
    return jdbcClient
        .sql(
            """
            select id, session_id, sequence, payload::text as payload
              from game_input_outbox
             where dispatched_at is null and session_id = :sessionId
             order by id
            """)
        .param("sessionId", sessionId)
        .query(
            (rs, n) ->
                new OutboxRow(
                    rs.getLong("id"),
                    rs.getObject("session_id", UUID.class),
                    rs.getObject("sequence") == null ? null : rs.getLong("sequence"),
                    rs.getString("payload")))
        .list();
  }

  /**
   * Allocates the row's {@code sequence} once, per {@code data-model.md} §4.2 — never re-rolled.
   */
  long allocateOutboxSequence(long outboxRowId, long sequence) {
    jdbcClient
        .sql(
            "update game_input_outbox set sequence = :sequence where id = :id and sequence is null")
        .param("sequence", sequence)
        .param("id", outboxRowId)
        .update();
    return jdbcClient
        .sql("select sequence from game_input_outbox where id = :id")
        .param("id", outboxRowId)
        .query(Long.class)
        .single();
  }

  /**
   * The acting player of {@code SUBMIT_ACTION}, read from the durable room state (never from the
   * client): a current, non-spectator participant of the room whose active session this is.
   * Grace-expired, kicked and departed players have {@code left_at} set and so are absent.
   */
  Optional<ActingParticipant> actingParticipant(UUID sessionId, UUID userId) {
    return jdbcClient
        .sql(
            """
            select rp.role, rp.team_id, au.username
            from room r
              join room_participant rp on rp.room_id = r.room_id
              join app_user au on au.user_id = rp.user_id
            where r.active_session_id = :sessionId and r.state = 'IN_PROGRESS'
              and rp.user_id = :userId and rp.left_at is null
            """)
        .param("sessionId", sessionId)
        .param("userId", userId)
        .query(
            (rs, rowNum) ->
                new ActingParticipant(
                    rs.getString("role"),
                    rs.getObject("team_id", UUID.class),
                    rs.getString("username")))
        .optional();
  }

  /**
   * The current participants (not left) of the room whose active session this is, as {@code
   * (userId, role, teamId)} rows; empty when no room claims the session. Deliberately not
   * restricted to {@code IN_PROGRESS}: the initial effects are fanned out before the room's own
   * commit.
   */
  List<SessionParticipant> sessionParticipants(UUID sessionId) {
    return jdbcClient
        .sql(
            """
            select rp.user_id, rp.role, rp.team_id
            from room r
              join room_participant rp on rp.room_id = r.room_id
            where r.active_session_id = :sessionId and rp.left_at is null
            """)
        .param("sessionId", sessionId)
        .query(
            (rs, rowNum) ->
                new SessionParticipant(
                    rs.getObject("user_id", UUID.class),
                    rs.getString("role"),
                    rs.getObject("team_id", UUID.class)))
        .list();
  }

  /**
   * One read of everything the move path asks about a session's room: its state and every current
   * participant, with the username the acting player is shown as. {@link ParticipantCache} stores
   * it; empty when no room claims the session.
   */
  Optional<SessionRoomView> sessionRoomView(UUID sessionId) {
    List<SessionRoomRow> rows =
        jdbcClient
            .sql(
                """
                select r.room_id, r.state, rp.user_id, rp.role, rp.team_id, au.username
                from room r
                  join room_participant rp on rp.room_id = r.room_id
                  join app_user au on au.user_id = rp.user_id
                where r.active_session_id = :sessionId and rp.left_at is null
                order by rp.join_order
                """)
            .param("sessionId", sessionId)
            .query(
                (rs, rowNum) ->
                    new SessionRoomRow(
                        rs.getObject("room_id", UUID.class),
                        rs.getString("state"),
                        new SessionRoomView.Member(
                            rs.getObject("user_id", UUID.class),
                            rs.getString("role"),
                            rs.getObject("team_id", UUID.class),
                            rs.getString("username"))))
            .list();
    if (rows.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(
        new SessionRoomView(
            rows.get(0).roomId(),
            sessionId,
            rows.get(0).state(),
            rows.stream().map(SessionRoomRow::member).toList()));
  }

  /** The room whose in-progress game this session is; empty once the game has ended. */
  Optional<UUID> inProgressRoomOf(UUID sessionId) {
    return jdbcClient
        .sql(
            "select room_id from room where active_session_id = :sessionId and state = 'IN_PROGRESS'")
        .param("sessionId", sessionId)
        .query(UUID.class)
        .optional();
  }

  /** {@code data-model.md} §4.2: is a room still claiming this session as active? */
  boolean sessionAttached(UUID sessionId) {
    return jdbcClient
        .sql("select 1 from room where active_session_id = :sessionId")
        .param("sessionId", sessionId)
        .query(Integer.class)
        .optional()
        .isPresent();
  }

  void markOutboxDispatched(long outboxRowId) {
    jdbcClient
        .sql("update game_input_outbox set dispatched_at = now() where id = :id")
        .param("id", outboxRowId)
        .update();
  }

  void recordOutboxAttemptFailure(long outboxRowId, String error) {
    jdbcClient
        .sql(
            "update game_input_outbox set attempts = attempts + 1, last_error = :error"
                + " where id = :id")
        .param("error", error)
        .param("id", outboxRowId)
        .update();
  }

  // ---------------------------------------------------------------------
  // Reconciliation (IMP-M2-10, room-lifecycle.md §5.2/§5.3/§5.4) — ReconciliationJob's five duties
  // and the closed-room purge.
  // ---------------------------------------------------------------------

  /** Duty (a)'s query: owed teardowns due for a re-drive attempt. */
  List<DueTeardown> dueSessionTeardowns(int limit) {
    return jdbcClient
        .sql(
            """
            select session_id, room_id, reason from session_teardown
             where next_attempt_at <= now()
             order by next_attempt_at
             limit :limit
             for update skip locked
            """)
        .param("limit", limit)
        .query(
            (rs, n) ->
                new DueTeardown(
                    rs.getObject("session_id", UUID.class),
                    rs.getObject("room_id", UUID.class),
                    rs.getString("reason")))
        .list();
  }

  /** {@code terminateAndCleanup} step (b): the confirmed teardown is no longer owed. */
  void deleteSessionTeardown(UUID sessionId) {
    jdbcClient
        .sql("delete from session_teardown where session_id = :sessionId")
        .param("sessionId", sessionId)
        .update();
  }

  /**
   * Age in seconds of the oldest owed teardown, {@code 0} when none is owed — the {@code
   * TeardownOwed} alert's input (FR-ALERT-007).
   */
  double oldestSessionTeardownAgeSeconds() {
    Double age =
        jdbcClient
            .sql(
                "select coalesce(extract(epoch from now() - min(requested_at)), 0)"
                    + " from session_teardown")
            .query(Double.class)
            .single();
    return age == null ? 0 : age;
  }

  /** The room whose active session this is, for the game-end caller. */
  Optional<UUID> roomOfActiveSession(UUID sessionId) {
    return jdbcClient
        .sql("select room_id from room where active_session_id = :sessionId")
        .param("sessionId", sessionId)
        .query(UUID.class)
        .optional();
  }

  /** {@code turnbase.teardown.owed} (§6): outstanding teardown debt, independent of due-ness. */
  long countSessionTeardownOwed() {
    Long count = jdbcClient.sql("select count(*) from session_teardown").query(Long.class).single();
    return count == null ? 0 : count;
  }

  /**
   * {@code room-lifecycle.md} §4's {@code terminateAndCleanup} step (c), moved here from either
   * caller per the pseudocode's own placement: {@code attempts++}, {@code last_error}, and a
   * backoff of 10s doubling to a 5-minute cap, computed from the row's pre-update {@code attempts}
   * so the very first failure backs off 10s, the second 20s, and so on.
   */
  void recordSessionTeardownFailure(UUID sessionId, String error) {
    jdbcClient
        .sql(
            """
            update session_teardown
               set attempts = attempts + 1,
                   last_error = :error,
                   next_attempt_at = now()
                     + make_interval(secs => least(10 * power(2, attempts), 300)::double precision)
             where session_id = :sessionId
            """)
        .param("error", error)
        .param("sessionId", sessionId)
        .update();
  }

  /**
   * Duty (b)'s health check, one query per page of sessions: of {@code sessionIds}, those still
   * named as {@code active_session_id} by a non-closed room. The rest are orphaned
   * (`room-lifecycle.md` §5.2 — "whose room no longer exists or is closed").
   */
  Set<UUID> sessionsOfOpenRooms(Collection<UUID> sessionIds) {
    if (sessionIds.isEmpty()) {
      return Set.of();
    }
    return Set.copyOf(
        jdbcClient
            .sql(
                "select active_session_id from room"
                    + " where active_session_id in (:sessionIds) and state <> 'CLOSED'")
            .param("sessionIds", sessionIds)
            .query(UUID.class)
            .list());
  }

  /** {@code data-model.md} §4.3: the first-observation time of each flagged session in the page. */
  Map<UUID, Instant> orphanedSessionFirstObservedAt(Collection<UUID> sessionIds) {
    if (sessionIds.isEmpty()) {
      return Map.of();
    }
    Map<UUID, Instant> observed = new HashMap<>();
    jdbcClient
        .sql(
            "select session_id, first_observed_at from orphaned_session_flag"
                + " where session_id in (:sessionIds)")
        .param("sessionIds", sessionIds)
        .query(
            rs -> {
              observed.put(
                  rs.getObject("session_id", UUID.class),
                  rs.getTimestamp("first_observed_at").toInstant());
            });
    return observed;
  }

  /** {@code data-model.md} §4.3's first-observation insert, one statement, a repeat is a no-op. */
  void flagOrphanedSessionsIfAbsent(Collection<UUID> sessionIds) {
    if (sessionIds.isEmpty()) {
      return;
    }
    StringBuilder sql = new StringBuilder("insert into orphaned_session_flag (session_id) values ");
    Map<String, Object> params = new HashMap<>();
    int i = 0;
    for (UUID sessionId : sessionIds) {
      sql.append(i == 0 ? "" : ", ").append("(:s").append(i).append(')');
      params.put("s" + i, sessionId);
      i++;
    }
    sql.append(" on conflict (session_id) do nothing");
    jdbcClient.sql(sql.toString()).params(params).update();
  }

  void clearOrphanedSessionFlags(Collection<UUID> sessionIds) {
    if (sessionIds.isEmpty()) {
      return;
    }
    jdbcClient
        .sql("delete from orphaned_session_flag where session_id in (:sessionIds)")
        .param("sessionIds", sessionIds)
        .update();
  }

  /**
   * Duty (c)'s candidates: an {@code IN_PROGRESS} room still naming a session. {@link
   * ReconciliationJob} checks the Game Host's own {@code session:{sessionId}} Redis record for each
   * — "no Game Host probe" (`room-lifecycle.md` §5.2): the record is the source of truth.
   */
  List<RoomSessionPair> inProgressRoomsWithSession() {
    return jdbcClient
        .sql(
            "select room_id, active_session_id from room"
                + " where state = 'IN_PROGRESS' and active_session_id is not null"
                + " for update skip locked")
        .query(
            (rs, n) ->
                new RoomSessionPair(
                    rs.getObject("room_id", UUID.class),
                    rs.getObject("active_session_id", UUID.class)))
        .list();
  }

  /** Duty (d)'s candidates: an idle Lobby room, past the configured idle window. */
  List<UUID> idleLobbyRoomIds(Duration idleWindow) {
    return jdbcClient
        .sql(
            """
            select room_id from room r
             where state = 'LOBBY'
               and last_activity_at <= now() - make_interval(secs => :idleSeconds)
               and not exists (select 1 from room_participant rp
                                where rp.room_id = r.room_id and rp.connection_count > 0
                                  and rp.left_at is null)
             for update skip locked
            """)
        .param("idleSeconds", (int) idleWindow.toSeconds())
        .query(UUID.class)
        .list();
  }

  /** §5.4 guard 2, shared by duties (c)/(d): first observation, guarded against overwriting it. */
  Optional<Instant> roomReconcileFlaggedAt(UUID roomId) {
    return jdbcClient
        .sql("select reconcile_flagged_at from room where room_id = :roomId")
        .param("roomId", roomId)
        .query(Timestamp.class)
        .optional()
        .map(Timestamp::toInstant);
  }

  void flagRoomForReconcileIfAbsent(UUID roomId) {
    jdbcClient
        .sql(
            "update room set reconcile_flagged_at = now()"
                + " where room_id = :roomId and reconcile_flagged_at is null")
        .param("roomId", roomId)
        .update();
  }

  /**
   * Duty (c)'s healthy rooms, one statement for a page of them: the flag is cleared where it is set
   * and nowhere else, so a healthy tick writes no rows.
   */
  void clearRoomReconcileFlags(Collection<UUID> roomIds) {
    if (roomIds.isEmpty()) {
      return;
    }
    jdbcClient
        .sql(
            "update room set reconcile_flagged_at = null"
                + " where room_id in (:roomIds) and reconcile_flagged_at is not null")
        .param("roomIds", roomIds)
        .update();
  }

  /** Cleared "as soon as the room looks healthy" (§5.4 guard 2) — never left dangling. */
  void clearRoomReconcileFlag(UUID roomId) {
    jdbcClient
        .sql("update room set reconcile_flagged_at = null where room_id = :roomId")
        .param("roomId", roomId)
        .update();
  }

  /** Duty (e)'s first pass: every instance currently named as an owner of a live room. */
  List<String> distinctLiveOwnerInstanceIds() {
    return jdbcClient
        .sql(
            "select distinct owner_instance_id from room"
                + " where owner_instance_id is not null and state <> 'CLOSED'")
        .query(String.class)
        .list();
  }

  /**
   * Duty (e)'s candidates (`room-lifecycle.md` §5.2): owned by a now-dead instance, or released and
   * still unclaimed (`ownerless_since` set by §1.3's graceful release).
   */
  List<UUID> unownedRoomIds(List<String> deadOwnerInstanceIds) {
    if (deadOwnerInstanceIds.isEmpty()) {
      return jdbcClient
          .sql(
              "select room_id from room where state <> 'CLOSED'"
                  + " and owner_instance_id is null and ownerless_since is not null"
                  + " for update skip locked")
          .query(UUID.class)
          .list();
    }
    return jdbcClient
        .sql(
            """
            select room_id from room
             where state <> 'CLOSED'
               and ( owner_instance_id in (:deadOwners)
                  or (owner_instance_id is null and ownerless_since is not null) )
             for update skip locked
            """)
        .param("deadOwners", deadOwnerInstanceIds)
        .query(UUID.class)
        .list();
  }

  /**
   * The subset of {@code roomIds} that exist and are not {@code CLOSED}: one query per sweep batch
   * (`room-lifecycle.md` §5.2, orphaned room keys).
   */
  Set<UUID> openRoomIds(Collection<UUID> roomIds) {
    if (roomIds.isEmpty()) {
      return Set.of();
    }
    return Set.copyOf(
        jdbcClient
            .sql("select room_id from room where room_id in (:roomIds) and state <> 'CLOSED'")
            .param("roomIds", roomIds)
            .query(UUID.class)
            .list());
  }

  /** FR-JOB-009 / §5.3: rooms Closed past the retention window; participant/team/invite cascade. */
  int purgeClosedRooms(Duration retention) {
    return jdbcClient
        .sql(
            "delete from room where state = 'CLOSED'"
                + " and closed_at <= now() - make_interval(secs => :seconds)")
        .param("seconds", (int) retention.toSeconds())
        .update();
  }

  /**
   * @param dispatchInline whether to attempt the dispatch once after the surrounding transaction
   *     commits (IMP-M9-05; {@code data-model.md} §4.2's "inline attempt at detach time"). The
   *     sweep stays the retry path either way.
   */
  void insertPlayerLeftOutbox(
      UUID roomId, UUID playerId, @Nullable UUID teamId, String reason, boolean dispatchInline) {
    UUID sessionId =
        jdbcClient
            .sql("select active_session_id from room where room_id = :roomId")
            .param("roomId", roomId)
            .query(UUID.class)
            .optional()
            .orElse(null);
    if (sessionId == null) {
      return;
    }
    String username = reads.usernameOf(playerId);
    Map<String, Object> payload =
        Map.of(
            "type",
            "player_left",
            "playerId",
            playerId.toString(),
            "displayName",
            username,
            "teamId",
            teamId == null ? "" : teamId.toString(),
            "reason",
            reason);
    String payloadJson;
    try {
      payloadJson = objectMapper.writeValueAsString(payload);
    } catch (Exception e) {
      throw new IllegalStateException("Failed to serialize player_left outbox payload", e);
    }
    jdbcClient
        .sql(
            "insert into game_input_outbox (session_id, payload) values (:sessionId, :payload::jsonb)")
        .param("sessionId", sessionId)
        .param("payload", payloadJson)
        .update();
    if (dispatchInline) {
      events.publishEvent(new PlayerLeftQueued(sessionId));
    }
  }
}
