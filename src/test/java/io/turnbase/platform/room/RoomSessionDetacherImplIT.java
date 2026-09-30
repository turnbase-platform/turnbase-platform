package io.turnbase.platform.room;

import static org.assertj.core.api.Assertions.assertThat;

import io.turnbase.platform.PostgresIntegrationTest;
import io.turnbase.platform.TurnBaseApplication;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@code IMP-M2-09}'s own Done-when, exercised directly against the real {@link
 * RoomSessionDetacher} rather than through one of its five callers: the fenced, version-bumping
 * `room` update (`room-lifecycle.md` §4 step 3), FR-INV-008's invitation deletion at closure (not
 * the seven-day purge cascade), and step 2's idempotency guard. {@code GraceDeadlineSweepJobIT} and
 * {@code RoomControllerIT} cover the two wired callers end to end.
 */
class RoomSessionDetacherImplIT extends PostgresIntegrationTest {

  private @Nullable ConfigurableApplicationContext context;

  @AfterEach
  void stopApplication() {
    if (context != null) {
      context.close();
    }
  }

  @Test
  void closingARoomDeletesItsPendingInvitationsRatherThanWaitingForThePurgeCascade() {
    context = boot();
    RoomSessionDetacher detacher = context.getBean(RoomSessionDetacher.class);
    UUID roomId = insertRoom(context);
    context.getBean(RoomOwnershipService.class).claim(roomId);
    insertInvitation(context, roomId);

    detacher.detach(roomId, null, "NO_PLAYERS_REMAINING", "CLOSED");

    Map<String, Object> room = roomRow(context, roomId);
    assertThat(room.get("state")).isEqualTo("CLOSED");
    assertThat(room.get("closed_reason")).isEqualTo("NO_PLAYERS_REMAINING");
    assertThat(roomVersion(context, roomId)).isEqualTo(1L);
    Long invitationCount =
        context
            .getBean(JdbcClient.class)
            .sql("select count(*) from room_invitation where room_id = :roomId")
            .param("roomId", roomId)
            .query(Long.class)
            .single();
    assertThat(invitationCount).isZero();
  }

  @Test
  void aSecondDetachForAnAlreadyDetachedSessionIsASafeNoOp() {
    context = boot();
    RoomSessionDetacher detacher = context.getBean(RoomSessionDetacher.class);
    UUID roomId = insertRoom(context);
    UUID sessionId = UUID.randomUUID();
    setActiveSession(context, roomId, sessionId);
    context.getBean(RoomOwnershipService.class).claim(roomId);

    detacher.detach(roomId, sessionId, "NO_PLAYERS_REMAINING", "CLOSED");
    // Step 2's idempotency check: room.active_session_id is already null, not sessionId, so a
    // second caller (e.g. a retried sweep tick) finds nothing to do rather than double-closing.
    detacher.detach(roomId, sessionId, "NO_PLAYERS_REMAINING", "CLOSED");

    assertThat(roomVersion(context, roomId)).isEqualTo(1L);
  }

  @Test
  void theRetryLimitFailsTheRoomBackToLobbyWithNoClosedReason() {
    context = boot();
    UUID roomId = insertRoom(context);
    UUID sessionId = UUID.randomUUID();
    context
        .getBean(JdbcClient.class)
        .sql("update room set state = 'IN_PROGRESS' where room_id = :roomId")
        .param("roomId", roomId)
        .update();
    setActiveSession(context, roomId, sessionId);
    context.getBean(RoomOwnershipService.class).claim(roomId);

    // By class: GameInputServiceIT's @Primary recorder is component-scanned into every IT context.
    context.getBean(RoomSessionFailureListener.class).onRetryLimitReached(sessionId);

    Map<String, Object> room = roomRow(context, roomId);
    assertThat(room.get("state")).isEqualTo("LOBBY");
    assertThat(room.get("closed_reason")).isNull();
    assertThat(room.get("active_session_id")).isNull();
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

  private static UUID insertRoom(ConfigurableApplicationContext context) {
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
                + " VALUES (:roomId, :gameId, '1.0.0', 'PUBLIC', 'LOBBY')")
        .param("roomId", roomId)
        .param("gameId", gameId)
        .update();
    return roomId;
  }

  private static void insertInvitation(ConfigurableApplicationContext context, UUID roomId) {
    JdbcClient jdbcClient = context.getBean(JdbcClient.class);
    UUID inviter = insertUser(context);
    UUID invitee = insertUser(context);
    jdbcClient
        .sql(
            """
            insert into room_invitation (invitation_id, room_id, inviter_user_id, invitee_user_id,
                                          expires_at)
            values (gen_random_uuid(), :roomId, :inviter, :invitee, now() + interval '1 day')
            """)
        .param("roomId", roomId)
        .param("inviter", inviter)
        .param("invitee", invitee)
        .update();
  }

  private static UUID insertUser(ConfigurableApplicationContext context) {
    UUID userId = UUID.randomUUID();
    String username = "user-" + userId.toString().substring(0, 8);
    context
        .getBean(JdbcClient.class)
        .sql(
            "insert into app_user (user_id, kind, username, username_skeleton, guest_serial)"
                + " values (:userId, 'GUEST', :username, :username, :serial)")
        .param("userId", userId)
        .param("username", username)
        .param("serial", userId.getLeastSignificantBits() & Long.MAX_VALUE)
        .update();
    return userId;
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

  private static Map<String, Object> roomRow(ConfigurableApplicationContext context, UUID roomId) {
    return context
        .getBean(JdbcClient.class)
        .sql("SELECT * FROM room WHERE room_id = :roomId")
        .param("roomId", roomId)
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
