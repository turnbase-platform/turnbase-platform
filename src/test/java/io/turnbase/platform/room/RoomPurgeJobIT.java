package io.turnbase.platform.room;

import static org.assertj.core.api.Assertions.assertThat;

import io.turnbase.platform.PostgresIntegrationTest;
import io.turnbase.platform.TurnBaseApplication;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * FR-JOB-009 / {@code room-lifecycle.md} §5.3 — closed rooms past the retention window are
 * permanently deleted, cascading to participant/team rows; game history carries no room foreign key
 * and is untouched (FR-RET-006, not exercised here since no history feature exists yet).
 */
class RoomPurgeJobIT extends PostgresIntegrationTest {

  private @Nullable ConfigurableApplicationContext context;

  @AfterEach
  void stopApplication() {
    if (context != null) {
      context.close();
    }
  }

  @Test
  void deletesAClosedRoomPastRetentionCascadingToParticipantsAndTeams() {
    context = boot();
    UUID roomId = insertClosedRoom(context, "2 days");
    UUID teamId = insertTeam(context, roomId);
    insertParticipant(context, roomId, teamId);

    context.getBean(RoomPurgeJob.class).run();

    JdbcClient jdbcClient = context.getBean(JdbcClient.class);
    assertThat(
            jdbcClient
                .sql("select count(*) from room where room_id = :roomId")
                .param("roomId", roomId)
                .query(Long.class)
                .single())
        .isZero();
    assertThat(
            jdbcClient
                .sql("select count(*) from room_participant where room_id = :roomId")
                .param("roomId", roomId)
                .query(Long.class)
                .single())
        .isZero();
    assertThat(
            jdbcClient
                .sql("select count(*) from room_team where room_id = :roomId")
                .param("roomId", roomId)
                .query(Long.class)
                .single())
        .isZero();
  }

  @Test
  void leavesAClosedRoomStillWithinRetention() {
    context = boot();
    UUID roomId = insertClosedRoom(context, "1 second");

    context.getBean(RoomPurgeJob.class).run();

    assertThat(
            context
                .getBean(JdbcClient.class)
                .sql("select count(*) from room where room_id = :roomId")
                .param("roomId", roomId)
                .query(Long.class)
                .single())
        .isEqualTo(1L);
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
        "--turnbase.jobs.deadline-sweep.interval=1h",
        "--turnbase.jobs.reconcile.interval=1h",
        // Keeps the background purge tick from racing this test's own manual job.run() calls.
        "--turnbase.jobs.room-purge.interval=1h",
        "--turnbase.retention.closed-room=1d");
  }

  private static UUID insertClosedRoom(ConfigurableApplicationContext context, String closedAgo) {
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
            "INSERT INTO room (room_id, game_id, game_version, visibility, state, closed_at,"
                + " closed_reason)"
                + " VALUES (:roomId, :gameId, '1.0.0', 'PUBLIC', 'CLOSED',"
                + " now() - CAST(:closedAgo AS interval), 'IDLE_TIMEOUT')")
        .param("roomId", roomId)
        .param("gameId", gameId)
        .param("closedAgo", closedAgo)
        .update();
    return roomId;
  }

  private static UUID insertTeam(ConfigurableApplicationContext context, UUID roomId) {
    UUID teamId = UUID.randomUUID();
    context
        .getBean(JdbcClient.class)
        .sql(
            "insert into room_team (team_id, room_id, template_index, team_index, configured_size)"
                + " values (:teamId, :roomId, 0, 0, 4)")
        .param("teamId", teamId)
        .param("roomId", roomId)
        .update();
    return teamId;
  }

  private static void insertParticipant(
      ConfigurableApplicationContext context, UUID roomId, UUID teamId) {
    UUID userId = UUID.randomUUID();
    JdbcClient jdbcClient = context.getBean(JdbcClient.class);
    jdbcClient
        .sql(
            "insert into app_user (user_id, kind, username, username_skeleton, guest_serial)"
                + " values (:userId, 'GUEST', :username, :username, :serial)")
        .param("userId", userId)
        .param("username", "user-" + userId.toString().substring(0, 8))
        .param("serial", userId.getLeastSignificantBits() & Long.MAX_VALUE)
        .update();
    jdbcClient
        .sql(
            "insert into room_participant (room_id, user_id, role, team_id, join_order)"
                + " values (:roomId, :userId, 'PLAYER', :teamId, 1)")
        .param("roomId", roomId)
        .param("userId", userId)
        .param("teamId", teamId)
        .update();
  }
}
