package io.turnbase.platform.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.turnbase.platform.PostgresIntegrationTest;
import io.turnbase.platform.SharedApplication;
import io.turnbase.platform.TurnBaseApplication;
import io.turnbase.platform.UsesSharedApplication;
import io.turnbase.platform.common.AuthenticatedUser;
import io.turnbase.platform.game.GameInputService;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code IMP-M18-04} / {@code load-testing-phase-b.md} §3.3: a move by a participant the owning
 * instance has already seen issues no Postgres statement, and anything that changes the room — a
 * mutation through the executor, a loss of ownership — sends the next move back to Postgres.
 *
 * <p>Requirements protected: {@code platform-operations.md} FR-DBQ-001, NFR-DBQ-004.
 */
@UsesSharedApplication
class MoveParticipantCacheIT extends PostgresIntegrationTest {

  private @Nullable ConfigurableApplicationContext context;
  private final GameInputService inputs = mock(GameInputService.class);
  private final SimpMessagingTemplate messaging = mock(SimpMessagingTemplate.class);

  @Test
  void aMoveByACachedParticipantIssuesNoPostgresStatement() {
    context = boot();
    Fixture room = fixture();
    GameActionController controller = controller();
    when(inputs.submitAction(eq(room.sessionId()), any()))
        .thenReturn(new GameInputService.Result(GameInputService.Outcome.DELIVERED, null));

    StatementCounter.STATEMENTS.clear();
    controller.handle(room.sessionId(), action(), principal(room.player()));
    assertThat(StatementCounter.STATEMENTS).as("the first move reads Postgres").isNotEmpty();

    StatementCounter.STATEMENTS.clear();
    controller.handle(room.sessionId(), action(), principal(room.player()));
    controller.handle(room.sessionId(), action(), principal(room.other()));
    assertThat(ctx().getBean(RoomSessionRosterSource.class).rosterOf(room.sessionId()))
        .hasValueSatisfying(
            roster -> {
              assertThat(roster.hasPlayer(room.player())).isTrue();
              assertThat(roster.hasPlayer(room.other())).isTrue();
            });
    assertThat(StatementCounter.STATEMENTS).as("later moves and the fan-out roster").isEmpty();
    verify(inputs, times(3)).submitAction(eq(room.sessionId()), any());
  }

  @Test
  void aMutationThroughTheExecutorSendsTheNextMoveBackToPostgres() {
    context = boot();
    Fixture room = fixture();
    GameActionController controller = controller();
    when(inputs.submitAction(eq(room.sessionId()), any()))
        .thenReturn(new GameInputService.Result(GameInputService.Outcome.DELIVERED, null));
    controller.handle(room.sessionId(), action(), principal(room.player()));

    ctx()
        .getBean(RoomEventExecutor.class)
        .execute(
            room.roomId(),
            () -> {
              ctx()
                  .getBean(JdbcClient.class)
                  .sql(
                      "update room_participant set left_at = now()"
                          + " where room_id = :roomId and user_id = :userId")
                  .param("roomId", room.roomId())
                  .param("userId", room.player())
                  .update();
              return new RoomEventExecutor.MutationOutcome<>(Boolean.TRUE, null);
            });

    StatementCounter.STATEMENTS.clear();
    controller.handle(room.sessionId(), action(), principal(room.player()));
    assertThat(StatementCounter.STATEMENTS).as("the departed player's move reads").isNotEmpty();
    verify(inputs, times(1)).submitAction(eq(room.sessionId()), any());
    verify(messaging).convertAndSendToUser(any(), eq("/queue/errors"), any());
    controller.handle(room.sessionId(), action(), principal(room.other()));
    verify(inputs, times(2)).submitAction(eq(room.sessionId()), any());
  }

  @Test
  void losingTheRoomSendsTheNextMoveBackToPostgres() {
    context = boot();
    Fixture room = fixture();
    GameActionController controller = controller();
    when(inputs.submitAction(eq(room.sessionId()), any()))
        .thenReturn(new GameInputService.Result(GameInputService.Outcome.DELIVERED, null));
    controller.handle(room.sessionId(), action(), principal(room.player()));

    ctx().getBean(RoomOwnershipService.class).release(room.roomId());

    StatementCounter.STATEMENTS.clear();
    controller.handle(room.sessionId(), action(), principal(room.player()));
    assertThat(StatementCounter.STATEMENTS).as("not the owner any more").isNotEmpty();
  }

  private GameActionController controller() {
    return new GameActionController(
        ctx().getBean(RoomOutboxRepository.class),
        ctx().getBean(ParticipantCache.class),
        inputs,
        messaging,
        JsonMapper.builder().build());
  }

  private static RoomCommandEnvelope action() {
    return new RoomCommandEnvelope(
        "SUBMIT_ACTION", "c-1", JsonMapper.builder().build().createObjectNode());
  }

  private static AuthenticatedUser principal(UUID userId) {
    return new AuthenticatedUser(userId, UUID.randomUUID());
  }

  private record Fixture(UUID roomId, UUID sessionId, UUID player, UUID other) {}

  private Fixture fixture() {
    UUID roomId = insertRoom("IN_PROGRESS");
    ctx().getBean(RoomOwnershipService.class).claim(roomId);
    UUID sessionId = UUID.randomUUID();
    JdbcClient jdbc = ctx().getBean(JdbcClient.class);
    jdbc.sql("update room set active_session_id = :sessionId where room_id = :roomId")
        .param("sessionId", sessionId)
        .param("roomId", roomId)
        .update();
    UUID team = UUID.randomUUID();
    jdbc.sql(
            "insert into room_team (team_id, room_id, template_index, team_index, configured_size)"
                + " values (:teamId, :roomId, 0, 0, 2)")
        .param("teamId", team)
        .param("roomId", roomId)
        .update();
    UUID player = insertParticipant(roomId, 1, team);
    UUID other = insertParticipant(roomId, 2, team);
    return new Fixture(roomId, sessionId, player, other);
  }

  private ConfigurableApplicationContext ctx() {
    return Objects.requireNonNull(context);
  }

  private ConfigurableApplicationContext boot() {
    StatementCounter.STATEMENTS.clear();
    return SharedApplication.run(
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

  private UUID insertParticipant(UUID roomId, long joinOrder, UUID teamId) {
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
            "insert into room_participant (room_id, user_id, role, team_id, join_order,"
                + " connection_count) values (:roomId, :userId, 'PLAYER', :teamId, :joinOrder, 1)")
        .param("roomId", roomId)
        .param("userId", userId)
        .param("joinOrder", joinOrder)
        .param("teamId", teamId)
        .update();
    return userId;
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
