package io.turnbase.platform.notification;

import static org.assertj.core.api.Assertions.assertThat;

import io.turnbase.platform.PostgresIntegrationTest;
import io.turnbase.platform.TurnBaseApplication;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * FR-JOB-002 and FR-JOB-013 (IR-20): the generic sweep never removes an actionable notification,
 * and the expiry purge — by cascade — is what does.
 */
class NotificationPurgeJobsIT extends PostgresIntegrationTest {

  private @Nullable ConfigurableApplicationContext context;
  private JdbcClient jdbc;
  private UUID userA;
  private UUID userB;

  @BeforeEach
  void boot() {
    ConfigurableApplicationContext booted =
        SpringApplication.run(
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
            "--turnbase.jobs.notification-purge.interval=1h",
            "--turnbase.jobs.expiry-purge.interval=1h",
            "--turnbase.retention.notification=30d");
    context = booted;
    jdbc = booted.getBean(JdbcClient.class);
    userA = insertUser();
    userB = insertUser();
  }

  @AfterEach
  void stop() {
    if (context != null) {
      context.close();
    }
  }

  @Test
  void notificationPurgeDeletesOnlyOldSubjectlessRows() {
    UUID old = insertNotification("GAME_APPROVED", "40 days", null);
    UUID fresh = insertNotification("GAME_APPROVED", "1 day", null);
    UUID requestId = insertFriendRequest("1 day");
    UUID actionable = insertNotification("FRIEND_REQUEST", "40 days", requestId);

    bean(NotificationPurgeJob.class).run();

    assertThat(exists("notification", "notification_id", old)).isFalse();
    assertThat(exists("notification", "notification_id", fresh)).isTrue();
    assertThat(exists("notification", "notification_id", actionable)).isTrue();
  }

  @Test
  void expiryPurgeRemovesExpiredSubjectsAndCascadesTheirNotifications() {
    UUID expired = insertFriendRequest("-1 hour");
    UUID expiredNotification = insertNotification("FRIEND_REQUEST", "40 days", expired);

    bean(ExpiredSubjectPurgeJob.class).run();

    assertThat(exists("friend_request", "request_id", expired)).isFalse();
    assertThat(exists("notification", "notification_id", expiredNotification)).isFalse();
  }

  @Test
  void expiryPurgeLeavesALiveSubjectAndItsNotification() {
    UUID live = insertFriendRequest("1 day");
    UUID notification = insertNotification("FRIEND_REQUEST", "40 days", live);

    bean(ExpiredSubjectPurgeJob.class).run();

    assertThat(exists("friend_request", "request_id", live)).isTrue();
    assertThat(exists("notification", "notification_id", notification)).isTrue();
  }

  private <T> T bean(Class<T> type) {
    return java.util.Objects.requireNonNull(context).getBean(type);
  }

  private UUID insertUser() {
    UUID id = UUID.randomUUID();
    String name = "u" + id.toString().substring(0, 8);
    jdbc.sql(
            "insert into app_user (user_id, kind, username, username_skeleton, email, email_source)"
                + " values (:id, 'REGISTERED', :n, :n, :e, 'SELF')")
        .param("id", id)
        .param("n", name)
        .param("e", name + "@example.test")
        .update();
    return id;
  }

  private UUID insertFriendRequest(String expiresIn) {
    UUID id = UUID.randomUUID();
    UUID sender = insertUser();
    jdbc.sql(
            "insert into friend_request (request_id, sender_id, recipient_id, expires_at)"
                + " values (:id, :s, :r, now() + cast(:x as interval))")
        .param("id", id)
        .param("s", sender)
        .param("r", userA)
        .param("x", expiresIn)
        .update();
    return id;
  }

  private UUID insertNotification(String type, String age, @Nullable UUID friendRequestId) {
    UUID id = UUID.randomUUID();
    jdbc.sql(
            "insert into notification (notification_id, user_id, type, friend_request_id,"
                + " created_at) values (:id, :u, :t, :fr, now() - cast(:age as interval))")
        .param("id", id)
        .param("u", userB)
        .param("t", type)
        .param("fr", friendRequestId)
        .param("age", age)
        .update();
    return id;
  }

  private boolean exists(String table, String column, UUID id) {
    return jdbc.sql("select count(*) from " + table + " where " + column + " = :id")
            .param("id", id)
            .query(Long.class)
            .single()
        == 1L;
  }
}
