package io.turnbase.platform.room;

import static org.assertj.core.api.Assertions.assertThat;

import io.turnbase.platform.PostgresIntegrationTest;
import io.turnbase.platform.SharedApplication;
import io.turnbase.platform.UsesSharedApplication;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@code IMP-M2-07}'s own Done-when: persisted absolute deadlines swept with no in-process timer,
 * the Lobby carve-out (FR-DC-007), the IR-06/IR-09 conditional details, and all four §5.1 duties
 * run in order on one schedule. {@code RoomOwnershipServiceIT} carries the adoption half
 * (`IMP-GATE-48`).
 */
@UsesSharedApplication
class GraceDeadlineSweepJobIT extends PostgresIntegrationTest {

  private @Nullable ConfigurableApplicationContext context;

  @Test
  void aLobbyDisconnectIsAnImmediateLeaveWithNoReservation() {
    context = boot();
    RoomService roomService = context.getBean(RoomService.class);
    UUID roomId = insertRoom(context, "LOBBY");
    context.getBean(RoomOwnershipService.class).claim(roomId);
    UUID userId = insertParticipant(context, roomId, "PLAYER", 1, 1);

    roomService.onDisconnect(roomId, userId);

    Map<String, Object> row = participantRow(context, roomId, userId);
    assertThat(row.get("left_at")).isNotNull();
    assertThat(row.get("leave_reason")).isEqualTo("LEFT");
    assertThat(row.get("grace_reservation_id")).isNull();
  }

  @Test
  void aSpectatorDisconnectIsAlwaysAnImmediateLeaveEvenInProgress() {
    context = boot();
    RoomService roomService = context.getBean(RoomService.class);
    UUID roomId = insertRoom(context, "IN_PROGRESS");
    context.getBean(RoomOwnershipService.class).claim(roomId);
    UUID userId = insertParticipant(context, roomId, "SPECTATOR", 1, 1);

    roomService.onDisconnect(roomId, userId);

    Map<String, Object> row = participantRow(context, roomId, userId);
    assertThat(row.get("left_at")).isNotNull();
    assertThat(row.get("grace_reservation_id")).isNull();
  }

  @Test
  void aMultiTabDisconnectThatStillHasAnotherConnectionOpensNoReservation() {
    context = boot();
    RoomService roomService = context.getBean(RoomService.class);
    UUID roomId = insertRoom(context, "IN_PROGRESS");
    context.getBean(RoomOwnershipService.class).claim(roomId);
    UUID userId = insertParticipant(context, roomId, "PLAYER", 1, 2); // two tabs open

    roomService.onDisconnect(roomId, userId);

    Map<String, Object> row = participantRow(context, roomId, userId);
    assertThat(((Number) Objects.requireNonNull(row.get("connection_count"))).intValue())
        .isEqualTo(1);
    assertThat(row.get("left_at")).isNull();
    assertThat(row.get("grace_reservation_id")).isNull();
  }

  @Test
  void anInProgressPlayerDisconnectOpensAGraceReservationAndBumpsVersion() {
    context = boot();
    RoomService roomService = context.getBean(RoomService.class);
    UUID roomId = insertRoom(context, "IN_PROGRESS");
    context.getBean(RoomOwnershipService.class).claim(roomId);
    UUID userId = insertParticipant(context, roomId, "PLAYER", 1, 1);

    roomService.onDisconnect(roomId, userId);

    Map<String, Object> row = participantRow(context, roomId, userId);
    assertThat(((Number) Objects.requireNonNull(row.get("connection_count"))).intValue()).isZero();
    assertThat(row.get("left_at")).isNull();
    assertThat(row.get("grace_reservation_id")).isNotNull();
    assertThat(row.get("grace_expires_at")).isNotNull();
    assertThat(roomVersion(context, roomId)).isEqualTo(1L);
  }

  @Test
  void hostDisconnectTransfersImmediatelyWhenAnotherHumanIsConnected() {
    context = boot();
    RoomService roomService = context.getBean(RoomService.class);
    UUID roomId = insertRoom(context, "IN_PROGRESS");
    // Claim before anyone connects — matching the real flow, where subscribe-time ownership
    // claim always precedes connection_count's first increment. Claiming after would instead
    // exercise IMP-GATE-48's crash-recovery adoption path (RoomOwnershipServiceIT's own test).
    context.getBean(RoomOwnershipService.class).claim(roomId);
    UUID host = insertParticipant(context, roomId, "PLAYER", 1, 1);
    UUID otherHuman = insertParticipant(context, roomId, "PLAYER", 2, 1);
    setHost(context, roomId, host);
    // Every claim opens a 20s migration window (§1.4 step 7), fresh claim or not — in real
    // gameplay it always elapses long before a game is even started. Clear it here to represent
    // that, isolating this test to FR-HDISC-001's own condition rather than FR-HDISC-004's.
    clearMigrationWindow(context, roomId);

    roomService.onDisconnect(roomId, host);

    assertThat(roomRow(context, roomId).get("host_user_id")).isEqualTo(otherHuman);
    // PLAYER_DISCONNECTED (v1) then HOST_TRANSFERRED (v2) — two distinct events.
    assertThat(roomVersion(context, roomId)).isEqualTo(2L);
  }

  @Test
  void hostDisconnectDefersTransferWithNoOtherHumanConnected() {
    context = boot();
    RoomService roomService = context.getBean(RoomService.class);
    UUID roomId = insertRoom(context, "IN_PROGRESS");
    context.getBean(RoomOwnershipService.class).claim(roomId);
    UUID host = insertParticipant(context, roomId, "PLAYER", 1, 1);
    setHost(context, roomId, host);

    roomService.onDisconnect(roomId, host);

    assertThat(roomRow(context, roomId).get("host_user_id")).isEqualTo(host);
    assertThat(roomVersion(context, roomId)).isEqualTo(1L); // only PLAYER_DISCONNECTED
  }

  @Test
  void theSweepResolvesADueExpiryThenClosesTheRoomAndDiscardsItsOwnOutboxRow() {
    context = boot();
    GraceDeadlineSweepJob sweep = context.getBean(GraceDeadlineSweepJob.class);
    RoomOwnershipService ownership = context.getBean(RoomOwnershipService.class);
    UUID roomId = insertRoom(context, "IN_PROGRESS");
    UUID sessionId = UUID.randomUUID();
    setActiveSession(context, roomId, sessionId);
    // The session's host is registered but unreachable, so the inline teardown stays owed.
    var redis = context.getBean(org.springframework.data.redis.core.StringRedisTemplate.class);
    redis.opsForHash().put("host:dead-host", "address", "127.0.0.1:1");
    redis.opsForValue().set("routing:" + sessionId, "dead-host");
    ownership.claim(roomId);
    UUID userId = insertParticipant(context, roomId, "PLAYER", 1, 0);
    openExpiredReservation(context, roomId, userId);

    sweep.run();

    Map<String, Object> row = participantRow(context, roomId, userId);
    assertThat(row.get("left_at")).isNotNull();
    assertThat(row.get("leave_reason")).isEqualTo("GRACE_EXPIRED");
    assertThat(row.get("lost_announced_at")).isNotNull();
    assertThat(row.get("grace_reservation_id")).isNull();

    // The sole human's reservation expiring leaves no player remaining, so the same tick closes
    // the room (room-lifecycle.md §3.3 step 4): PLAYER_LOST (v1) then ROOM_CLOSED (v2).
    Map<String, Object> closedRoom = roomRow(context, roomId);
    assertThat(closedRoom.get("state")).isEqualTo("CLOSED");
    assertThat(closedRoom.get("closed_reason")).isEqualTo("GRACE_EXPIRED_NO_HUMAN");
    assertThat(closedRoom.get("active_session_id")).isNull();
    assertThat(roomVersion(context, roomId)).isEqualTo(2L);

    // The outbox row the expiry itself inserted is discarded by detachSession step 5 — a
    // player_left for a session about to be torn down is moot (data-model.md §4.2).
    Long outboxCount =
        context
            .getBean(JdbcClient.class)
            .sql("select count(*) from game_input_outbox where session_id = :sessionId")
            .param("sessionId", sessionId)
            .query(Long.class)
            .single();
    assertThat(outboxCount).isZero();

    Long teardownCount =
        context
            .getBean(JdbcClient.class)
            .sql("select count(*) from session_teardown where session_id = :sessionId")
            .param("sessionId", sessionId)
            .query(Long.class)
            .single();
    assertThat(teardownCount).isEqualTo(1L);
  }

  @Test
  void theSweepNeverFiresOrClosesInALobbyRoomWithNoSession() {
    context = boot();
    GraceDeadlineSweepJob sweep = context.getBean(GraceDeadlineSweepJob.class);
    RoomOwnershipService ownership = context.getBean(RoomOwnershipService.class);
    // A Lobby disconnect never opens a reservation in the first place (FR-DC-002/FR-DC-007), so
    // the only way to prove the carve-out is to force one directly, as if the room transitioned
    // after the reservation opened, and confirm the sweep still resolves it correctly rather than
    // re-deleting a Lobby room.
    UUID roomId = insertRoom(context, "LOBBY");
    ownership.claim(roomId);
    UUID userId = insertParticipant(context, roomId, "PLAYER", 1, 0);
    openExpiredReservation(context, roomId, userId);

    sweep.run();

    // The generic resolve statement itself is state-agnostic (room-lifecycle.md §3.2), so it still
    // resolves — what §3.3 step 2/4 exempt in Lobby (active_session_id IS NULL) is the outbox
    // insert and the closure evaluation: closing here would re-delete every Lobby room a rolling
    // deploy's mass adoption (§1.4 step 4) opens a reservation in, exactly what FR-DC-007 forbids.
    Long outboxCount =
        context
            .getBean(JdbcClient.class)
            .sql("select count(*) from game_input_outbox")
            .query(Long.class)
            .single();
    assertThat(outboxCount).isZero();
    assertThat(roomRow(context, roomId).get("state")).isEqualTo("LOBBY");
  }

  @Test
  void deferredTailsAnnouncePlayerLostAndTransferHostToTheNextConnectedHuman() {
    context = boot();
    GraceDeadlineSweepJob sweep = context.getBean(GraceDeadlineSweepJob.class);
    RoomOwnershipService ownership = context.getBean(RoomOwnershipService.class);
    UUID roomId = insertRoom(context, "IN_PROGRESS");
    ownership.claim(roomId);
    UUID departedHost = insertParticipant(context, roomId, "PLAYER", 1, 0);
    UUID otherHuman = insertParticipant(context, roomId, "PLAYER", 2, 1);
    setHost(context, roomId, departedHost);
    // Simulate a foreign instance's EARLY_RELEASE: left_at set, lost_announced_at left null.
    context
        .getBean(JdbcClient.class)
        .sql(
            "update room_participant set left_at = now(), leave_reason = 'EARLY_RELEASE'"
                + " where room_id = :roomId and user_id = :userId")
        .param("roomId", roomId)
        .param("userId", departedHost)
        .update();

    sweep.run();

    Map<String, Object> row = participantRow(context, roomId, departedHost);
    assertThat(row.get("lost_announced_at")).isNotNull();
    assertThat(roomRow(context, roomId).get("host_user_id")).isEqualTo(otherHuman);
  }

  @Test
  void elapsedMigrationWindowTransfersHostWhenDisconnectedAndClearsTheWindow() {
    context = boot();
    GraceDeadlineSweepJob sweep = context.getBean(GraceDeadlineSweepJob.class);
    RoomOwnershipService ownership = context.getBean(RoomOwnershipService.class);
    UUID roomId = insertRoom(context, "IN_PROGRESS");
    ownership.claim(roomId);
    UUID disconnectedHost = insertParticipant(context, roomId, "PLAYER", 1, 0);
    UUID otherHuman = insertParticipant(context, roomId, "PLAYER", 2, 1);
    setHost(context, roomId, disconnectedHost);
    setElapsedMigrationWindow(context, roomId);

    sweep.run();

    Map<String, Object> row = roomRow(context, roomId);
    assertThat(row.get("migration_window_expires_at")).isNull();
    assertThat(row.get("host_user_id")).isEqualTo(otherHuman);
  }

  private static ConfigurableApplicationContext boot() {
    return SharedApplication.run(
        "--server.port=0",
        "--management.server.port=0",
        "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
        "--spring.datasource.username=" + POSTGRES.getUsername(),
        "--spring.datasource.password=" + POSTGRES.getPassword(),
        "--turnbase.artifact-store.endpoint=" + MINIO.getS3URL(),
        "--turnbase.artifact-store.bucket=" + ARTIFACT_BUCKET,
        "--turnbase.artifact-store.access-key=" + MINIO.getUserName(),
        "--turnbase.artifact-store.secret-key=" + MINIO.getPassword(),
        "--turnbase.admin.username=admin",
        "--turnbase.admin.password=admin",
        "--spring.data.redis.url=" + redisUrl(),
        "--turnbase.room.lease.renew-interval=1h",
        // Keeps the background sweep tick from racing this test's own manual sweep.run() calls.
        "--turnbase.jobs.deadline-sweep.interval=1h");
  }

  private static UUID insertRoom(ConfigurableApplicationContext context, String state) {
    UUID gameId = UUID.randomUUID();
    UUID roomId = UUID.randomUUID();
    JdbcClient jdbcClient = context.getBean(JdbcClient.class);
    jdbcClient
        .sql(
            "INSERT INTO game (game_id, title, short_description, badge, owner_user_id)"
                + " VALUES (:gameId, 'Test Game', 'A game for testing.', 'OFFICIAL', NULL)")
        .param("gameId", gameId)
        .update();
    jdbcClient
        .sql(
            "INSERT INTO room (room_id, game_id, game_version, visibility, state)"
                + " VALUES (:roomId, :gameId, '1.0.0', 'PUBLIC', :state)")
        .param("roomId", roomId)
        .param("gameId", gameId)
        .param("state", state)
        .update();
    return roomId;
  }

  private static UUID insertParticipant(
      ConfigurableApplicationContext context,
      UUID roomId,
      String role,
      long joinOrder,
      int connectionCount) {
    UUID userId = UUID.randomUUID();
    String username = "user" + joinOrder + "-" + userId.toString().substring(0, 8);
    JdbcClient jdbcClient = context.getBean(JdbcClient.class);
    jdbcClient
        .sql(
            "insert into app_user (user_id, kind, username, username_skeleton, guest_serial)"
                + " values (:userId, 'GUEST', :username, :username, :serial)")
        .param("userId", userId)
        .param("username", username)
        .param("serial", userId.getLeastSignificantBits() & Long.MAX_VALUE)
        .update();
    jdbcClient
        .sql(
            "insert into room_participant (room_id, user_id, role, join_order, connection_count)"
                + " values (:roomId, :userId, :role, :joinOrder, :connectionCount)")
        .param("roomId", roomId)
        .param("userId", userId)
        .param("role", role)
        .param("joinOrder", joinOrder)
        .param("connectionCount", connectionCount)
        .update();
    return userId;
  }

  private static void setHost(
      ConfigurableApplicationContext context, UUID roomId, UUID hostUserId) {
    context
        .getBean(JdbcClient.class)
        .sql("update room set host_user_id = :hostUserId where room_id = :roomId")
        .param("hostUserId", hostUserId)
        .param("roomId", roomId)
        .update();
  }

  private static void setActiveSession(
      ConfigurableApplicationContext context, UUID roomId, UUID sessionId) {
    context
        .getBean(JdbcClient.class)
        .sql("update room set active_session_id = :sessionId where room_id = :roomId")
        .param("sessionId", sessionId)
        .param("roomId", roomId)
        .update();
  }

  private static void openExpiredReservation(
      ConfigurableApplicationContext context, UUID roomId, UUID userId) {
    context
        .getBean(JdbcClient.class)
        .sql(
            "update room_participant set grace_reservation_id = gen_random_uuid(),"
                + " grace_expires_at = now() - interval '5 seconds'"
                + " where room_id = :roomId and user_id = :userId")
        .param("roomId", roomId)
        .param("userId", userId)
        .update();
  }

  private static void clearMigrationWindow(ConfigurableApplicationContext context, UUID roomId) {
    context
        .getBean(JdbcClient.class)
        .sql("update room set migration_window_expires_at = null where room_id = :roomId")
        .param("roomId", roomId)
        .update();
  }

  private static void setElapsedMigrationWindow(
      ConfigurableApplicationContext context, UUID roomId) {
    context
        .getBean(JdbcClient.class)
        .sql(
            "update room set migration_window_expires_at = now() - interval '5 seconds'"
                + " where room_id = :roomId")
        .param("roomId", roomId)
        .update();
  }

  private static Map<String, Object> roomRow(ConfigurableApplicationContext context, UUID roomId) {
    return context
        .getBean(JdbcClient.class)
        .sql("SELECT * FROM room WHERE room_id = :roomId")
        .param("roomId", roomId)
        .query()
        .singleRow();
  }

  private static Map<String, Object> participantRow(
      ConfigurableApplicationContext context, UUID roomId, UUID userId) {
    return context
        .getBean(JdbcClient.class)
        .sql("select * from room_participant where room_id = :roomId and user_id = :userId")
        .param("roomId", roomId)
        .param("userId", userId)
        .query()
        .singleRow();
  }

  private static long roomVersion(ConfigurableApplicationContext context, UUID roomId) {
    Number version =
        context
            .getBean(JdbcClient.class)
            .sql("select version from room where room_id = :roomId")
            .param("roomId", roomId)
            .query(Number.class)
            .single();
    return version.longValue();
  }
}
