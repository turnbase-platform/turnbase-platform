package io.turnbase.platform.room;

import static org.assertj.core.api.Assertions.assertThat;

import io.grpc.Server;
import io.grpc.Status;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import io.turnbase.grpc.gamehost.v1.GameHostServiceGrpc;
import io.turnbase.grpc.gamehost.v1.ProcessInputRequest;
import io.turnbase.grpc.gamehost.v1.ProcessInputResponse;
import io.turnbase.grpc.gamehost.v1.ProcessResult;
import io.turnbase.grpc.gamehost.v1.ProcessStatus;
import io.turnbase.platform.PostgresIntegrationTest;
import io.turnbase.platform.SharedApplication;
import io.turnbase.platform.UsesSharedApplication;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@code IMP-M9-05} (CA-05): a confirmed leave reaches the engine right after its transaction
 * commits, with no sweep tick; a failed inline attempt is recorded and left to the sweep.
 *
 * <p>Requirements protected: {@code game-rooms.md} FR-LIFE-004; {@code reconnection-flow.md}
 * FR-DC-005, FR-EXPIRY-002.
 */
@UsesSharedApplication
class InlineLeaveDispatchIT extends PostgresIntegrationTest {

  private static final List<ProcessInputRequest> RECEIVED = new CopyOnWriteArrayList<>();
  private static volatile boolean hostHealthy = true;

  private static ConfigurableApplicationContext context;
  private static Server fakeHost;
  private static JdbcClient jdbc;

  @BeforeAll
  static void boot() throws IOException {
    fakeHost =
        NettyServerBuilder.forPort(0)
            .addService(
                new GameHostServiceGrpc.GameHostServiceImplBase() {
                  @Override
                  public void processInput(
                      ProcessInputRequest request, StreamObserver<ProcessInputResponse> out) {
                    if (!hostHealthy) {
                      out.onError(Status.UNAVAILABLE.asRuntimeException());
                      return;
                    }
                    RECEIVED.add(request);
                    out.onNext(
                        ProcessInputResponse.newBuilder()
                            .setResult(
                                ProcessResult.newBuilder()
                                    .setStatus(ProcessStatus.PROCESS_STATUS_OK))
                            .build());
                    out.onCompleted();
                  }
                })
            .build()
            .start();
    context =
        SharedApplication.run(
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
            // Any delivery in these tests must come from the inline attempt, not the sweep tick.
            "--turnbase.jobs.deadline-sweep.interval=1h");
    jdbc = context.getBean(JdbcClient.class);
    // HostRegistry is package-private to the game module; its keys are the registered shape.
    StringRedisTemplate redis = context.getBean(StringRedisTemplate.class);
    redis.opsForSet().add("hosts", "leave-host");
    redis.opsForHash().put("host:leave-host", "address", "127.0.0.1:" + fakeHost.getPort());
  }

  @AfterAll
  static void shutdown() {
    fakeHost.shutdownNow();
  }

  @BeforeEach
  void reset() {
    RECEIVED.clear();
    hostHealthy = true;
  }

  @Test
  void anExplicitLeaveReachesTheEngineWithoutWaitingForASweepTick() {
    Fixture f = fixture();

    context.getBean(RoomService.class).leave(f.leaver, f.roomId);

    assertThat(RECEIVED).hasSize(1);
    assertThat(RECEIVED.get(0).getSessionId()).isEqualTo(f.sessionId.toString());
    assertThat(RECEIVED.get(0).getPlayerLeft().getPlayer().getPlayerId())
        .isEqualTo(f.leaver.toString());
    Map<String, Object> row = outboxRow(f.sessionId);
    assertThat(row.get("dispatched_at")).isNotNull();
    assertThat(row.get("sequence")).isNotNull();
  }

  @Test
  void aFailedInlineAttemptIsRecordedAndTheSweepRetriesTheSameSequence() {
    hostHealthy = false;
    Fixture f = fixture();

    context.getBean(RoomService.class).leave(f.leaver, f.roomId);

    Map<String, Object> failed = outboxRow(f.sessionId);
    assertThat(failed.get("dispatched_at")).isNull();
    assertThat(number(failed, "attempts")).isEqualTo(1);
    assertThat(failed.get("last_error")).isNotNull();
    assertThat(RECEIVED).isEmpty();

    hostHealthy = true;
    context.getBean(GraceDeadlineSweepJob.class).run();

    assertThat(RECEIVED).hasSize(1);
    assertThat(RECEIVED.get(0).getSequence()).isEqualTo(number(failed, "sequence"));
    assertThat(outboxRow(f.sessionId).get("dispatched_at")).isNotNull();
  }

  private static long number(Map<String, Object> row, String column) {
    return ((Number) java.util.Objects.requireNonNull(row.get(column))).longValue();
  }

  private record Fixture(UUID roomId, UUID sessionId, UUID leaver) {}

  private static Fixture fixture() {
    UUID gameId = UUID.randomUUID();
    UUID roomId = UUID.randomUUID();
    UUID sessionId = UUID.randomUUID();
    jdbc.sql(
            "INSERT INTO game (game_id, title, short_description, badge, owner_user_id)"
                + " VALUES (:gameId, 'Test Game', 'A game for testing.', 'OFFICIAL', NULL)")
        .param("gameId", gameId)
        .update();
    jdbc.sql(
            "INSERT INTO room (room_id, game_id, game_version, visibility, state,"
                + " active_session_id) VALUES (:roomId, :gameId, '1.0.0', 'PUBLIC',"
                + " 'IN_PROGRESS', :sessionId)")
        .param("roomId", roomId)
        .param("gameId", gameId)
        .param("sessionId", sessionId)
        .update();
    StringRedisTemplate redis = context.getBean(StringRedisTemplate.class);
    redis.opsForValue().set("routing:" + sessionId, "leave-host");
    redis.opsForValue().set("seq:" + sessionId, "0");
    redis.opsForValue().set("delivered:" + sessionId, "0");
    redis.opsForValue().set("failcount:" + sessionId, "0");
    context.getBean(RoomOwnershipService.class).claim(roomId);
    UUID host = participant(roomId, 1);
    UUID leaver = participant(roomId, 2);
    jdbc.sql("update room set host_user_id = :host where room_id = :roomId")
        .param("host", host)
        .param("roomId", roomId)
        .update();
    return new Fixture(roomId, sessionId, leaver);
  }

  private static UUID participant(UUID roomId, long joinOrder) {
    UUID userId = UUID.randomUUID();
    String username = "user" + joinOrder + "-" + userId.toString().substring(0, 8);
    jdbc.sql(
            "insert into app_user (user_id, kind, username, username_skeleton, guest_serial)"
                + " values (:userId, 'GUEST', :username, :username, :serial)")
        .param("userId", userId)
        .param("username", username)
        .param("serial", userId.getLeastSignificantBits() & Long.MAX_VALUE)
        .update();
    jdbc.sql(
            "insert into room_participant (room_id, user_id, role, join_order, connection_count)"
                + " values (:roomId, :userId, 'PLAYER', :joinOrder, 1)")
        .param("roomId", roomId)
        .param("userId", userId)
        .param("joinOrder", joinOrder)
        .update();
    return userId;
  }

  private static Map<String, Object> outboxRow(UUID sessionId) {
    return jdbc.sql("select * from game_input_outbox where session_id = :sessionId")
        .param("sessionId", sessionId)
        .query()
        .singleRow();
  }
}
