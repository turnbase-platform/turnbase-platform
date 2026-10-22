package io.turnbase.platform.room;

import static org.assertj.core.api.Assertions.assertThat;

import io.turnbase.platform.PostgresIntegrationTest;
import io.turnbase.platform.TurnBaseApplication;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@code IMP-M17-01} / {@code FR-DBQ-004}: a deadline-sweep duty that selects a room must either
 * act on it or stop selecting it. The rooms this instance cannot mutate are the closed ones ({@code
 * RoomOwnershipService.onRoomClosed} drops the in-memory epoch and leaves {@code owner_instance_id}
 * and {@code migration_window_expires_at} set) and the released ones. The statements of several
 * consecutive ticks are counted at the datasource.
 */
class SweepSelectsOnlyRoomsItCanChangeIT extends PostgresIntegrationTest {

  private static final int TICKS = 4;

  private @Nullable ConfigurableApplicationContext context;

  @AfterEach
  void stopApplication() {
    if (context != null) {
      context.close();
    }
  }

  @Test
  void aRoomThatClosedInsideItsMigrationWindowIsNotReReadOnEveryTick() {
    context = boot();
    UUID roomId = insertRoom("IN_PROGRESS");
    claim(roomId);
    UUID host = insertParticipant(roomId, 1, 0);
    insertParticipant(roomId, 2, 0);
    setHost(roomId, host);
    close(roomId);
    setElapsedMigrationWindow(roomId);

    assertThat(roomLookups(ticks())).isZero();
    // The window is left alone: nothing may mutate a closed room.
    assertThat(roomRow(roomId, "state")).isEqualTo("CLOSED");
  }

  @Test
  void aReleasedRoomIsNotReReadOnEveryTick() {
    context = boot();
    UUID roomId = insertRoom("IN_PROGRESS");
    claim(roomId);
    UUID host = insertParticipant(roomId, 1, 0);
    insertParticipant(roomId, 2, 0);
    setHost(roomId, host);
    setElapsedMigrationWindow(roomId);
    ctx().getBean(RoomOwnershipService.class).release(roomId);

    assertThat(roomLookups(ticks())).isZero();
  }

  @Test
  void aClosedRoomIsSelectedByNoDuty() {
    context = boot();
    UUID roomId = insertRoom("IN_PROGRESS");
    claim(roomId);
    UUID departed = insertParticipant(roomId, 1, 0);
    UUID waiting = insertParticipant(roomId, 2, 0);
    setHost(roomId, departed);
    ctx()
        .getBean(JdbcClient.class)
        .sql(
            "update room_participant set left_at = now(), leave_reason = 'EARLY_RELEASE'"
                + " where room_id = :roomId and user_id = :userId")
        .param("roomId", roomId)
        .param("userId", departed)
        .update();
    ctx()
        .getBean(JdbcClient.class)
        .sql(
            "update room_participant set grace_reservation_id = gen_random_uuid(),"
                + " grace_expires_at = now() - interval '5 seconds'"
                + " where room_id = :roomId and user_id = :userId")
        .param("roomId", roomId)
        .param("userId", waiting)
        .update();
    setElapsedMigrationWindow(roomId);
    RoomRepository repository = ctx().getBean(RoomRepository.class);
    String pod = ctx().getBean(PodIdentity.class).id();
    assertThat(repository.dueGraceReservations(pod)).hasSize(1);
    assertThat(repository.deferredTails(pod)).hasSize(1);
    assertThat(repository.elapsedMigrationWindowRoomIds(pod)).containsExactly(roomId);

    close(roomId);

    assertThat(repository.dueGraceReservations(pod)).isEmpty();
    assertThat(repository.deferredTails(pod)).isEmpty();
    assertThat(repository.elapsedMigrationWindowRoomIds(pod)).isEmpty();
  }

  @Test
  void aRoomTheInstanceCanMutateStillHasItsWindowClosedAndItsHostTransferred() {
    context = boot();
    UUID roomId = insertRoom("IN_PROGRESS");
    claim(roomId);
    UUID disconnectedHost = insertParticipant(roomId, 1, 0);
    UUID otherHuman = insertParticipant(roomId, 2, 1);
    setHost(roomId, disconnectedHost);
    setElapsedMigrationWindow(roomId);

    ticks();

    assertThat(roomRow(roomId, "migration_window_expires_at")).isNull();
    assertThat(roomRow(roomId, "host_user_id")).isEqualTo(otherHuman);
  }

  /**
   * Runs {@link #TICKS} sweep ticks and returns the statements they issued. The first tick is the
   * one allowed to look at a room; the count returned is that of every tick after it.
   */
  private List<String> ticks() {
    GraceDeadlineSweepJob sweep = ctx().getBean(GraceDeadlineSweepJob.class);
    sweep.run();
    StatementCounter.STATEMENTS.clear();
    for (int i = 1; i < TICKS; i++) {
      sweep.run();
    }
    return List.copyOf(StatementCounter.STATEMENTS);
  }

  /** The three statements of {@code closeMigrationWindow}'s pre-read (OQ-005). */
  private static long roomLookups(List<String> statements) {
    return statements.stream()
        .filter(
            sql ->
                sql.contains("select host_user_id from room")
                    || sql.contains("select connection_count > 0 from room_participant")
                    || sql.contains("select user_id from room_participant"))
        .count();
  }

  private ConfigurableApplicationContext ctx() {
    return Objects.requireNonNull(context);
  }

  private void claim(UUID roomId) {
    ctx().getBean(RoomOwnershipService.class).claim(roomId);
  }

  private void close(UUID roomId) {
    ctx().getBean(RoomSessionDetacher.class).detach(roomId, null, "NO_PLAYERS_REMAINING", "CLOSED");
    assertThat(roomRow(roomId, "state")).isEqualTo("CLOSED");
  }

  private ConfigurableApplicationContext boot() {
    StatementCounter.STATEMENTS.clear();
    return SpringApplication.run(
        new Class<?>[] {TurnBaseApplication.class, StatementCounter.class},
        new String[] {
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
          // The background tick must not race this test's own sweep.run() calls.
          "--turnbase.jobs.deadline-sweep.interval=1h"
        });
  }

  private UUID insertRoom(String state) {
    UUID gameId = UUID.randomUUID();
    UUID roomId = UUID.randomUUID();
    JdbcClient jdbcClient = ctx().getBean(JdbcClient.class);
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

  private UUID insertParticipant(UUID roomId, long joinOrder, int connectionCount) {
    UUID userId = UUID.randomUUID();
    String username = "user" + joinOrder + "-" + userId.toString().substring(0, 8);
    JdbcClient jdbcClient = ctx().getBean(JdbcClient.class);
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
                + " values (:roomId, :userId, 'PLAYER', :joinOrder, :connectionCount)")
        .param("roomId", roomId)
        .param("userId", userId)
        .param("joinOrder", joinOrder)
        .param("connectionCount", connectionCount)
        .update();
    return userId;
  }

  private void setHost(UUID roomId, UUID hostUserId) {
    ctx()
        .getBean(JdbcClient.class)
        .sql("update room set host_user_id = :hostUserId where room_id = :roomId")
        .param("hostUserId", hostUserId)
        .param("roomId", roomId)
        .update();
  }

  private void setElapsedMigrationWindow(UUID roomId) {
    ctx()
        .getBean(JdbcClient.class)
        .sql(
            "update room set migration_window_expires_at = now() - interval '5 seconds'"
                + " where room_id = :roomId")
        .param("roomId", roomId)
        .update();
  }

  private @Nullable Object roomRow(UUID roomId, String column) {
    return ctx()
        .getBean(JdbcClient.class)
        .sql("SELECT * FROM room WHERE room_id = :roomId")
        .param("roomId", roomId)
        .query()
        .singleRow()
        .get(column);
  }

  /**
   * Records the SQL of every statement prepared through the application's datasource. Deliberately
   * not annotated: it is passed to {@code SpringApplication} as a source, so no other test's
   * component scan finds it.
   */
  static class StatementCounter {

    static final List<String> STATEMENTS = new CopyOnWriteArrayList<>();

    @Bean
    static BeanPostProcessor countingDataSource() {
      return new BeanPostProcessor() {
        @Override
        public Object postProcessAfterInitialization(Object bean, String beanName) {
          if (!(bean instanceof DataSource target) || Proxy.isProxyClass(bean.getClass())) {
            return bean;
          }
          return Proxy.newProxyInstance(
              bean.getClass().getClassLoader(),
              bean.getClass().getInterfaces(),
              (proxy, method, args) -> {
                Object result = invoke(target, method, args);
                return result instanceof Connection connection
                    ? countingConnection(connection)
                    : result;
              });
        }
      };
    }

    private static Connection countingConnection(Connection connection) {
      InvocationHandler handler =
          (proxy, method, args) -> {
            if (method.getName().equals("prepareStatement")
                && args != null
                && args[0] instanceof String sql) {
              STATEMENTS.add(sql.replaceAll("\\s+", " ").trim());
            }
            return invoke(connection, method, args);
          };
      return (Connection)
          Proxy.newProxyInstance(
              StatementCounter.class.getClassLoader(), new Class<?>[] {Connection.class}, handler);
    }

    private static @Nullable Object invoke(Object target, Method method, Object @Nullable [] args)
        throws Throwable {
      try {
        return method.invoke(target, args);
      } catch (InvocationTargetException e) {
        throw e.getCause();
      }
    }
  }
}
