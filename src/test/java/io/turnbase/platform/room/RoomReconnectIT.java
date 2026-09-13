package io.turnbase.platform.room;

import static org.assertj.core.api.Assertions.assertThat;

import io.turnbase.platform.PostgresIntegrationTest;
import io.turnbase.platform.TurnBaseApplication;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@code IMP-M2-08}'s own Done-when: a reconnect within the grace window resolves the reservation
 * and broadcasts {@code PLAYER_RECONNECTED} rather than re-seating the participant, mirrored from
 * {@code registerDisconnect} exactly as {@code room-lifecycle.md} §3.2 describes. {@code
 * RoomControllerIT} carries the REST-level half — rejoin after expiry as an ordinary join, and the
 * {@code IMP-GATE-50} row-reuse fix.
 */
class RoomReconnectIT extends PostgresIntegrationTest {

  private @Nullable ConfigurableApplicationContext context;

  @AfterEach
  void stopApplication() {
    if (context != null) {
      context.close();
    }
  }

  @Test
  void reconnectWithinTheGraceWindowClearsTheReservationAndBumpsVersion() {
    context = boot();
    RoomService roomService = context.getBean(RoomService.class);
    UUID roomId = insertRoom(context, "IN_PROGRESS");
    context.getBean(RoomOwnershipService.class).claim(roomId);
    UUID teamId = insertTeam(context, roomId);
    UUID userId = insertParticipant(context, roomId, "PLAYER", 1, 0, teamId);
    openLiveReservation(context, roomId, userId);

    roomService.onConnect(roomId, userId);

    Map<String, Object> row = participantRow(context, roomId, userId);
    assertThat(((Number) Objects.requireNonNull(row.get("connection_count"))).intValue())
        .isEqualTo(1);
    assertThat(row.get("grace_reservation_id")).isNull();
    assertThat(row.get("grace_expires_at")).isNull();
    assertThat(row.get("left_at")).isNull();
    // The seat and team are restored, not re-seated: same team_id, no new row, no new join_order.
    assertThat(row.get("team_id")).isEqualTo(teamId);
    assertThat(((Number) Objects.requireNonNull(row.get("join_order"))).longValue()).isEqualTo(1L);
    assertThat(roomVersion(context, roomId)).isEqualTo(1L);
  }

  @Test
  void anOrdinaryConnectionWithNoOutstandingReservationIsAPlainIncrementAndBumpsNoVersion() {
    context = boot();
    RoomService roomService = context.getBean(RoomService.class);
    UUID roomId = insertRoom(context, "LOBBY");
    context.getBean(RoomOwnershipService.class).claim(roomId);
    UUID userId = insertParticipant(context, roomId, "PLAYER", 1, 0, null);

    roomService.onConnect(roomId, userId);

    Map<String, Object> row = participantRow(context, roomId, userId);
    assertThat(((Number) Objects.requireNonNull(row.get("connection_count"))).intValue())
        .isEqualTo(1);
    assertThat(roomVersion(context, roomId)).isZero();
  }

  @Test
  void theFormerHostReturnsAsARegularPlayerWithNoFurtherHostEvent() {
    context = boot();
    RoomService roomService = context.getBean(RoomService.class);
    UUID roomId = insertRoom(context, "IN_PROGRESS");
    context.getBean(RoomOwnershipService.class).claim(roomId);
    UUID formerHost = insertParticipant(context, roomId, "PLAYER", 1, 0, null);
    UUID newHost = insertParticipant(context, roomId, "PLAYER", 2, 1, null);
    // room-websocket.md: HOST_TRANSFERRED already happened at disconnect time (FR-HDISC-001/002) —
    // simulate that by pointing host_user_id at the new host before the former host reconnects.
    setHost(context, roomId, newHost);
    openLiveReservation(context, roomId, formerHost);

    roomService.onConnect(roomId, formerHost);

    assertThat(roomRow(context, roomId).get("host_user_id")).isEqualTo(newHost);
    // Exactly one event (PLAYER_RECONNECTED) — no host event is re-emitted on reconnect.
    assertThat(roomVersion(context, roomId)).isEqualTo(1L);
  }

  @Test
  void aLostClaimBetweenSubscribeAndResolutionIsAQuietNoOp() {
    context = boot();
    RoomService roomService = context.getBean(RoomService.class);
    UUID roomId = insertRoom(context, "IN_PROGRESS");
    // Deliberately never claimed by this instance — mirrors onDisconnect's own lost-claim test.
    UUID userId = insertParticipant(context, roomId, "PLAYER", 1, 0, null);
    openLiveReservation(context, roomId, userId);

    roomService.onConnect(roomId, userId); // must not throw

    Map<String, Object> row = participantRow(context, roomId, userId);
    assertThat(row.get("grace_reservation_id")).isNotNull(); // untouched — the next owner's job
  }

  private static ConfigurableApplicationContext boot() {
    return SpringApplication.run(
        TurnBaseApplication.class,
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

  private static UUID insertTeam(ConfigurableApplicationContext context, UUID roomId) {
    UUID teamId = UUID.randomUUID();
    context
        .getBean(JdbcClient.class)
        .sql(
            "insert into room_team (team_id, room_id, template_index, team_index, configured_size)"
                + " values (:teamId, :roomId, 0, 0, 2)")
        .param("teamId", teamId)
        .param("roomId", roomId)
        .update();
    return teamId;
  }

  private static UUID insertParticipant(
      ConfigurableApplicationContext context,
      UUID roomId,
      String role,
      long joinOrder,
      int connectionCount,
      @Nullable UUID teamId) {
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
            "insert into room_participant (room_id, user_id, role, team_id, join_order,"
                + " connection_count) values (:roomId, :userId, :role, :teamId, :joinOrder,"
                + " :connectionCount)")
        .param("roomId", roomId)
        .param("userId", userId)
        .param("role", role)
        .param("teamId", teamId)
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

  private static void openLiveReservation(
      ConfigurableApplicationContext context, UUID roomId, UUID userId) {
    context
        .getBean(JdbcClient.class)
        .sql(
            "update room_participant set grace_reservation_id = gen_random_uuid(),"
                + " grace_expires_at = now() + interval '60 seconds'"
                + " where room_id = :roomId and user_id = :userId")
        .param("roomId", roomId)
        .param("userId", userId)
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
