package io.turnbase.platform.room;

import static org.assertj.core.api.Assertions.assertThat;

import io.grpc.Server;
import io.grpc.Status;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import io.micrometer.core.instrument.MeterRegistry;
import io.turnbase.grpc.gamehost.v1.GameHostServiceGrpc;
import io.turnbase.grpc.gamehost.v1.TerminateSessionRequest;
import io.turnbase.grpc.gamehost.v1.TerminateSessionResponse;
import io.turnbase.grpc.gamehost.v1.TerminationReason;
import io.turnbase.platform.PostgresIntegrationTest;
import io.turnbase.platform.TurnBaseApplication;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@code IMP-M3-08}: the owed teardown is driven to a real {@code TerminateSession}, cleared on
 * confirmation, kept (and aged) on failure, and the normal end of a game returns the room to Lobby
 * through the same {@code detachSession}.
 */
class SessionTeardownIT extends PostgresIntegrationTest {

  record Terminated(String sessionId, TerminationReason reason) {}

  private static final List<Terminated> TERMINATED = new CopyOnWriteArrayList<>();
  private static final AtomicBoolean HOST_UP = new AtomicBoolean(true);

  private static ConfigurableApplicationContext context;
  private static Server fakeHost;
  private static StringRedisTemplate redis;
  private static JdbcClient jdbc;

  @BeforeAll
  static void boot() throws IOException {
    fakeHost =
        NettyServerBuilder.forPort(0)
            .addService(
                new GameHostServiceGrpc.GameHostServiceImplBase() {
                  @Override
                  public void terminateSession(
                      TerminateSessionRequest request,
                      StreamObserver<TerminateSessionResponse> out) {
                    if (!HOST_UP.get()) {
                      out.onError(Status.UNAVAILABLE.asRuntimeException());
                      return;
                    }
                    TERMINATED.add(new Terminated(request.getSessionId(), request.getReason()));
                    out.onNext(
                        TerminateSessionResponse.newBuilder()
                            .setSessionId(request.getSessionId())
                            .build());
                    out.onCompleted();
                  }
                })
            .build()
            .start();
    context =
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
            "--turnbase.jobs.reconcile.startup-suppression=0s");
    redis = context.getBean(StringRedisTemplate.class);
    jdbc = context.getBean(JdbcClient.class);
    redis.opsForHash().put("host:teardown-host", "address", "127.0.0.1:" + fakeHost.getPort());
  }

  @AfterAll
  static void shutdown() {
    fakeHost.shutdownNow();
    context.close();
  }

  private static UUID insertRoom(String state) {
    UUID gameId = UUID.randomUUID();
    UUID roomId = UUID.randomUUID();
    jdbc.sql(
            "INSERT INTO game (game_id, title, short_description, badge, owner_user_id)"
                + " VALUES (:gameId, 'Test Game', 'A game for testing.', 'OFFICIAL', NULL)")
        .param("gameId", gameId)
        .update();
    jdbc.sql(
            "INSERT INTO room (room_id, game_id, game_version, visibility, state)"
                + " VALUES (:roomId, :gameId, '1.0.0', 'PUBLIC', :state)")
        .param("roomId", roomId)
        .param("gameId", gameId)
        .param("state", state)
        .update();
    return roomId;
  }

  /** A connected human, so the room a game returns to Lobby is not empty (and so closed). */
  private static void insertPlayer(UUID roomId) {
    UUID userId = UUID.randomUUID();
    jdbc.sql(
            "insert into app_user (user_id, kind, username, username_skeleton, guest_serial)"
                + " values (:userId, 'GUEST', :username, :username, :serial)")
        .param("userId", userId)
        .param("username", "user-" + userId.toString().substring(0, 8))
        .param("serial", userId.getLeastSignificantBits() & Long.MAX_VALUE)
        .update();
    jdbc.sql(
            "insert into room_participant (room_id, user_id, role, join_order, connection_count)"
                + " values (:roomId, :userId, 'PLAYER', 1, 1)")
        .param("roomId", roomId)
        .param("userId", userId)
        .update();
  }

  private static UUID sessionWithKeys() {
    UUID sessionId = UUID.randomUUID();
    for (String prefix :
        List.of("session:", "routing:", "gamekey:", "queue:", "seq:", "delivered:", "failcount:")) {
      redis.opsForValue().set(prefix + sessionId, "x");
    }
    return sessionId;
  }

  private static void insertDueTeardown(UUID sessionId, UUID roomId, String reason) {
    jdbc.sql(
            "insert into session_teardown (session_id, room_id, reason, next_attempt_at)"
                + " values (:s, :r, :reason, now() - interval '1 second')")
        .param("s", sessionId)
        .param("r", roomId)
        .param("reason", reason)
        .update();
  }

  private static boolean teardownRowExists(UUID sessionId) {
    return jdbc.sql("select count(*) from session_teardown where session_id = :s")
            .param("s", sessionId)
            .query(Long.class)
            .single()
        > 0;
  }

  private static boolean anyKeyLeft(UUID sessionId) {
    return List.of("session:", "routing:", "gamekey:", "queue:", "seq:", "delivered:", "failcount:")
        .stream()
        .anyMatch(prefix -> Boolean.TRUE.equals(redis.hasKey(prefix + sessionId)));
  }

  @Test
  void theSweepTerminatesOnTheRoutedHostThenDeletesTheRowAndTheWholeKeyFamily() {
    HOST_UP.set(true);
    UUID roomId = insertRoom("CLOSED");
    UUID sessionId = sessionWithKeys();
    redis.opsForValue().set("routing:" + sessionId, "teardown-host");
    insertDueTeardown(sessionId, roomId, "NO_PLAYERS_REMAINING");

    context.getBean(ReconciliationJob.class).run();

    assertThat(TERMINATED)
        .contains(
            new Terminated(sessionId.toString(), TerminationReason.TERMINATION_REASON_ROOM_CLOSED));
    assertThat(teardownRowExists(sessionId)).isFalse();
    assertThat(anyKeyLeft(sessionId)).isFalse();
  }

  @Test
  void anUnreachableHostLeavesTheRowOwedAndItsAgeIsExposed() {
    HOST_UP.set(false);
    UUID roomId = insertRoom("CLOSED");
    UUID sessionId = sessionWithKeys();
    redis.opsForValue().set("routing:" + sessionId, "teardown-host");
    insertDueTeardown(sessionId, roomId, "NO_PLAYERS_REMAINING");
    jdbc.sql(
            "update session_teardown set requested_at = now() - interval '20 minutes'"
                + " where session_id = :s")
        .param("s", sessionId)
        .update();

    context.getBean(ReconciliationJob.class).run();
    HOST_UP.set(true);

    Map<String, Object> row =
        jdbc.sql("select attempts, last_error from session_teardown where session_id = :s")
            .param("s", sessionId)
            .query()
            .singleRow();
    assertThat(row.get("attempts")).isEqualTo(1);
    assertThat(anyKeyLeft(sessionId)).isTrue();
    double age =
        context.getBean(MeterRegistry.class).get("turnbase.teardown.owed.age").gauge().value();
    assertThat(age).isGreaterThan(900);
    jdbc.sql("delete from session_teardown where session_id = :s").param("s", sessionId).update();
  }

  @Test
  void theNormalEndOfAGameReturnsTheRoomToLobbyAndTerminatesWithGameOver() {
    HOST_UP.set(true);
    UUID roomId = insertRoom("IN_PROGRESS");
    insertPlayer(roomId);
    UUID sessionId = sessionWithKeys();
    redis.opsForValue().set("routing:" + sessionId, "teardown-host");
    jdbc.sql("update room set active_session_id = :s where room_id = :r")
        .param("s", sessionId)
        .param("r", roomId)
        .update();
    context.getBean(RoomOwnershipService.class).claim(roomId);

    System.out.println(
        "DBG "
            + context.getBean(RoomRepository.class).roomOfActiveSession(sessionId)
            + " "
            + context.getBean(RoomOwnershipService.class).claim(roomId));
    // By type, not by interface: GameInputServiceIT's recording listener is @Primary and is
    // picked up by component scanning in every test context.
    RoomGameEndListener listener = context.getBean(RoomGameEndListener.class);
    listener.onGameOver(sessionId);
    listener.onGameOver(sessionId); // a replayed sequence announces the end again: a no-op

    Map<String, Object> room =
        jdbc.sql("select state, active_session_id, closed_reason from room where room_id = :r")
            .param("r", roomId)
            .query()
            .singleRow();
    assertThat(room.get("state")).isEqualTo("LOBBY");
    assertThat(room.get("active_session_id")).isNull();
    assertThat(room.get("closed_reason")).isNull();
    assertThat(TERMINATED)
        .filteredOn(t -> t.sessionId().equals(sessionId.toString()))
        .containsExactly(
            new Terminated(sessionId.toString(), TerminationReason.TERMINATION_REASON_GAME_OVER));
    assertThat(teardownRowExists(sessionId)).isFalse();
    assertThat(anyKeyLeft(sessionId)).isFalse();
  }
}
