package io.turnbase.platform.game;

import static org.assertj.core.api.Assertions.assertThat;

import io.grpc.Server;
import io.grpc.Status;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import io.turnbase.grpc.gamehost.v1.Effect;
import io.turnbase.grpc.gamehost.v1.GameFailure;
import io.turnbase.grpc.gamehost.v1.GameFailureKind;
import io.turnbase.grpc.gamehost.v1.GameHostServiceGrpc;
import io.turnbase.grpc.gamehost.v1.ProcessInputRequest;
import io.turnbase.grpc.gamehost.v1.ProcessInputResponse;
import io.turnbase.grpc.gamehost.v1.ProcessResult;
import io.turnbase.grpc.gamehost.v1.ProcessStatus;
import io.turnbase.platform.PostgresIntegrationTest;
import io.turnbase.platform.SharedApplication;
import io.turnbase.platform.TurnBaseApplication;
import io.turnbase.platform.UsesSharedApplication;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * {@code IMP-M3-05}: input dispatch against a fake in-process Game Host — the per-session sequence,
 * once-only effect delivery, and the failure counting of {@code orchestration-gamehost-protocol.md}
 * *Error model* / GAME-I3.
 */
@UsesSharedApplication
class GameInputServiceIT extends PostgresIntegrationTest {

  record Delivered(UUID sessionId, long sequence, int effects) {}

  static final List<Delivered> DELIVERED = new CopyOnWriteArrayList<>();
  static final java.util.Map<Long, String> WATERMARK_AT_DELIVERY =
      new java.util.concurrent.ConcurrentHashMap<>();
  static final List<String> COMMANDS = new CopyOnWriteArrayList<>();
  static final java.util.concurrent.atomic.AtomicBoolean FAIL_FANOUT =
      new java.util.concurrent.atomic.AtomicBoolean();
  static final List<UUID> GAME_OVERS = new CopyOnWriteArrayList<>();
  static final List<UUID> SESSION_FAILURES = new CopyOnWriteArrayList<>();

  @Configuration
  static class Recorder {
    @Bean
    @Primary
    EffectDelivery recordingDelivery(StringRedisTemplate redis) {
      return (sessionId, sequence, effects) -> {
        if (FAIL_FANOUT.get()) {
          throw new IllegalStateException("fan-out failed");
        }
        // The context's own template: this nested configuration is scanned into other ITs too.
        WATERMARK_AT_DELIVERY.put(sequence, redis.opsForValue().get("delivered:" + sessionId));
        DELIVERED.add(new Delivered(sessionId, sequence, effects.size()));
        return effects.stream()
            .filter(Effect::hasLog)
            .map(e -> new GameLogStore.Entry(sequence, effects.indexOf(e), e.getLog().getEntry()))
            .toList();
      };
    }

    @Bean
    @Primary
    GameEndListener recordingGameEnd() {
      return GAME_OVERS::add;
    }

    @Bean
    @Primary
    SessionFailureListener recordingSessionFailure() {
      return SESSION_FAILURES::add;
    }
  }

  private static volatile Function<ProcessInputRequest, Object> behaviour =
      request -> Status.UNIMPLEMENTED;

  private static ConfigurableApplicationContext context;
  private static Server fakeHost;
  private static GameInputService inputs;
  private static HostRegistry registry;
  private static StringRedisTemplate redis;

  @BeforeAll
  static void boot() throws IOException {
    fakeHost =
        NettyServerBuilder.forPort(0)
            .addService(
                new GameHostServiceGrpc.GameHostServiceImplBase() {
                  @Override
                  public void processInput(
                      ProcessInputRequest request, StreamObserver<ProcessInputResponse> out) {
                    Object answer = behaviour.apply(request);
                    if (answer instanceof Status status) {
                      out.onError(status.asRuntimeException());
                      return;
                    }
                    out.onNext(
                        ProcessInputResponse.newBuilder()
                            .setResult((ProcessResult) answer)
                            .build());
                    out.onCompleted();
                  }
                })
            .build()
            .start();
    context =
        SharedApplication.run(
            new Class<?>[] {TurnBaseApplication.class, Recorder.class},
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
              "--spring.data.redis.url=" + redisUrl()
            });
    inputs = context.getBean(GameInputService.class);
    registry = context.getBean(HostRegistry.class);
    var lettuce =
        (org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory)
            context.getBean(org.springframework.data.redis.connection.RedisConnectionFactory.class);
    java.util.Objects.requireNonNull(
            (io.lettuce.core.AbstractRedisClient) lettuce.getNativeClient())
        .addListener(
            new io.lettuce.core.event.command.CommandListener() {
              @Override
              public void commandSucceeded(
                  io.lettuce.core.event.command.CommandSucceededEvent event) {
                var command = event.getCommand();
                String args = command.getArgs() == null ? "" : command.getArgs().toCommandString();
                COMMANDS.add(command.getType().name() + " " + args);
              }
            });
    lettuce.resetConnection(); // connections made before the listener do not report to it
    redis = context.getBean(StringRedisTemplate.class);
    registry.register("input-host", "127.0.0.1:" + fakeHost.getPort(), List.of("jvm"));
  }

  @AfterAll
  static void shutdown() {
    fakeHost.shutdownNow();
  }

  private static UUID newSession() {
    UUID id = UUID.randomUUID();
    redis.opsForValue().set("routing:" + id, "input-host");
    redis.opsForValue().set("seq:" + id, "0");
    redis.opsForValue().set("delivered:" + id, "0");
    redis.opsForValue().set("failcount:" + id, "0");
    return id;
  }

  private static ProcessInputRequest input(UUID sessionId, long sequence) {
    return ProcessInputRequest.newBuilder()
        .setSessionId(sessionId.toString())
        .setSequence(sequence)
        .build();
  }

  private static ProcessResult ok(ProcessInputRequest request, int effects) {
    ProcessResult.Builder result =
        ProcessResult.newBuilder()
            .setSessionId(request.getSessionId())
            .setSequence(request.getSequence())
            .setStatus(ProcessStatus.PROCESS_STATUS_OK);
    for (int i = 0; i < effects; i++) {
      result.addEffects(Effect.getDefaultInstance());
    }
    return result.build();
  }

  private static ProcessResult failed(ProcessInputRequest request) {
    return ProcessResult.newBuilder()
        .setSessionId(request.getSessionId())
        .setSequence(request.getSequence())
        .setStatus(ProcessStatus.PROCESS_STATUS_FAILED)
        .setFailure(
            GameFailure.newBuilder()
                .setKind(GameFailureKind.GAME_FAILURE_KIND_HANDLER_EXCEPTION)
                .setMessage("boom"))
        .build();
  }

  @Test
  void submittedActionsReachTheHostInSequenceOrderEvenWhenSubmittedConcurrently() throws Exception {
    List<Long> arrived = new CopyOnWriteArrayList<>();
    behaviour =
        request -> {
          arrived.add(request.getSequence());
          return ok(request, 0);
        };
    UUID session = newSession();
    java.util.concurrent.ExecutorService pool =
        java.util.concurrent.Executors.newFixedThreadPool(8);
    try {
      List<java.util.concurrent.Future<GameInputService.Result>> results =
          new java.util.ArrayList<>();
      for (int i = 0; i < 40; i++) {
        results.add(
            pool.submit(
                () ->
                    inputs.submitAction(
                        session, io.turnbase.grpc.gamehost.v1.ActionInput.getDefaultInstance())));
      }
      for (var result : results) {
        assertThat(result.get().outcome()).isEqualTo(GameInputService.Outcome.DELIVERED);
      }
    } finally {
      pool.shutdownNow();
    }
    assertThat(arrived).hasSize(40).isSorted();
  }

  @Test
  void sequencesAreMonotonicPerSession() {
    UUID a = newSession();
    UUID b = newSession();
    assertThat(inputs.allocateSequence(a)).isEqualTo(1);
    assertThat(inputs.allocateSequence(a)).isEqualTo(2);
    assertThat(inputs.allocateSequence(b)).isEqualTo(1);
  }

  @Test
  void aSuccessDeliversItsEffectsOnceEvenWhenTheSequenceIsRedispatched() {
    behaviour = request -> ok(request, 2);
    UUID session = newSession();
    ProcessInputRequest request = input(session, inputs.allocateSequence(session));

    assertThat(inputs.process(request)).isEqualTo(GameInputService.Outcome.DELIVERED);
    assertThat(inputs.process(request)).isEqualTo(GameInputService.Outcome.DELIVERED);

    assertThat(DELIVERED).filteredOn(d -> d.sessionId().equals(session)).hasSize(1);
    assertThat(DELIVERED.getLast()).isEqualTo(new Delivered(session, 1, 2));
    assertThat(redis.opsForValue().get("delivered:" + session)).isEqualTo("1");
  }

  @Test
  void gameFailuresAreCountedConsecutivelyAndTheLimitIsReachedOnTheThird() {
    behaviour = GameInputServiceIT::failed;
    UUID session = newSession();

    assertThat(inputs.process(input(session, 1))).isEqualTo(GameInputService.Outcome.REJECTED);
    assertThat(inputs.process(input(session, 2))).isEqualTo(GameInputService.Outcome.REJECTED);
    assertThat(SESSION_FAILURES).doesNotContain(session);
    assertThat(inputs.process(input(session, 3)))
        .isEqualTo(GameInputService.Outcome.RETRY_LIMIT_REACHED);
    assertThat(DELIVERED).noneMatch(d -> d.sessionId().equals(session));
    assertThat(SESSION_FAILURES).containsExactly(session);
  }

  @Test
  void aRejectedActionNamesTheFailureKindAndNeverTheMessage() {
    behaviour = GameInputServiceIT::failed;
    UUID session = newSession();

    GameInputService.Result result =
        inputs.submitAction(session, io.turnbase.grpc.gamehost.v1.ActionInput.getDefaultInstance());

    assertThat(result.outcome()).isEqualTo(GameInputService.Outcome.REJECTED);
    assertThat(result.failureKind()).isEqualTo("HANDLER_EXCEPTION");
  }

  @Test
  void aSuccessResetsTheFailureCount() {
    UUID session = newSession();
    behaviour = GameInputServiceIT::failed;
    inputs.process(input(session, 1));
    inputs.process(input(session, 2));
    behaviour = request -> ok(request, 0);
    inputs.process(input(session, 3));
    behaviour = GameInputServiceIT::failed;

    assertThat(inputs.process(input(session, 4))).isEqualTo(GameInputService.Outcome.REJECTED);
    assertThat(redis.opsForValue().get("failcount:" + session)).isEqualTo("1");
  }

  @Test
  void transportStatusesMapToRetryNotFoundAndSessionFailed() {
    UUID session = newSession();
    behaviour = request -> Status.NOT_FOUND;
    assertThat(inputs.process(input(session, 1)))
        .isEqualTo(GameInputService.Outcome.SESSION_NOT_FOUND);
    behaviour = request -> Status.FAILED_PRECONDITION;
    assertThat(inputs.process(input(session, 2)))
        .isEqualTo(GameInputService.Outcome.SESSION_FAILED);
    behaviour = request -> Status.UNAVAILABLE;
    assertThat(inputs.process(input(session, 3))).isEqualTo(GameInputService.Outcome.RETRY);
    assertThat(redis.opsForValue().get("failcount:" + session)).isEqualTo("1");
  }

  @Test
  void anUnroutedSessionIsNotFound() {
    assertThat(inputs.process(input(UUID.randomUUID(), 1)))
        .isEqualTo(GameInputService.Outcome.SESSION_NOT_FOUND);
  }

  @Test
  void aDeliveredGameOverAnnouncesTheEndOnceForEachDispatchOfThatSequence() {
    behaviour =
        request ->
            ProcessResult.newBuilder()
                .setSessionId(request.getSessionId())
                .setSequence(request.getSequence())
                .setStatus(ProcessStatus.PROCESS_STATUS_OK)
                .addEffects(
                    Effect.newBuilder()
                        .setGameOver(
                            io.turnbase.grpc.gamehost.v1.GameOverEffect.getDefaultInstance()))
                .build();
    UUID session = newSession();

    assertThat(inputs.process(input(session, inputs.allocateSequence(session))))
        .isEqualTo(GameInputService.Outcome.DELIVERED);

    assertThat(GAME_OVERS).containsOnlyOnce(session);
  }

  @Test
  void aForfeitIsRecordedForEveryPlayerTheSessionStartedWithEvenAfterTheLeaverLeftTheRoom() {
    // RR-03: the leaver's room_participant row is already gone, so the room no longer names them.
    var jdbc = context.getBean(org.springframework.jdbc.core.simple.JdbcClient.class);
    UUID game = UUID.randomUUID();
    jdbc.sql(
            "insert into game (game_id, owner_user_id, title, short_description, badge, listed)"
                + " values (:id, null, 'Forfeit', 'x', 'OFFICIAL', false)")
        .param("id", game)
        .update();
    UUID leaver = registeredUser(jdbc);
    UUID winner = registeredUser(jdbc);
    UUID leaverTeam = UUID.randomUUID();
    UUID winnerTeam = UUID.randomUUID();
    UUID session = newSession();
    redis.opsForValue().set("gamekey:" + session, game + ":1.0.0");
    redis
        .opsForHash()
        .putAll(
            "roster:" + session,
            java.util.Map.of(
                leaver.toString(),
                leaverTeam.toString(),
                winner.toString(),
                winnerTeam.toString()));
    behaviour =
        request ->
            ProcessResult.newBuilder()
                .setSessionId(request.getSessionId())
                .setSequence(request.getSequence())
                .setStatus(ProcessStatus.PROCESS_STATUS_OK)
                .addEffects(
                    Effect.newBuilder()
                        .setGameOver(
                            io.turnbase.grpc.gamehost.v1.GameOverEffect.newBuilder()
                                .setOutcome(
                                    io.turnbase.grpc.gamehost.v1.GameOutcome.newBuilder()
                                        .addTeamResults(
                                            io.turnbase.grpc.gamehost.v1.TeamResult.newBuilder()
                                                .setTeamId(winnerTeam.toString())
                                                .setPlacement(1))
                                        .addTeamResults(
                                            io.turnbase.grpc.gamehost.v1.TeamResult.newBuilder()
                                                .setTeamId(leaverTeam.toString())
                                                .setPlacement(2))
                                        .addPlayerResults(
                                            io.turnbase.grpc.gamehost.v1.PlayerResult.newBuilder()
                                                .setPlayerId(leaver.toString())))))
                .build();

    inputs.process(input(session, inputs.allocateSequence(session)));

    assertThat(
            jdbc.sql(
                    "select user_id from game_history_entry where session_id = :s"
                        + " order by placement")
                .param("s", session)
                .query(UUID.class)
                .list())
        .containsExactly(winner, leaver);
    assertThat(
            jdbc.sql("select completed_session_count from game where game_id = :g")
                .param("g", game)
                .query(Long.class)
                .single())
        .isEqualTo(1);
  }

  private static UUID registeredUser(org.springframework.jdbc.core.simple.JdbcClient jdbc) {
    UUID id = UUID.randomUUID();
    String name = "u" + id.toString().replace("-", "").substring(0, 12);
    jdbc.sql(
            "insert into app_user (user_id, kind, username, username_skeleton, email, email_source)"
                + " values (:id, 'REGISTERED', :n, :n, :e, 'SELF')")
        .param("id", id)
        .param("n", name)
        .param("e", name + "@example.com")
        .update();
    return id;
  }

  @Test
  void anUnreachableHostMovesTheSessionToAnotherHostOfTheSameGameKey() {
    registry.register("spare-host", "127.0.0.1:" + fakeHost.getPort(), List.of("jvm"));
    registry.register("down-host", "127.0.0.1:1", List.of("jvm"));
    UUID session = newSession();
    redis.opsForValue().set("routing:" + session, "down-host");
    redis.opsForValue().set("gamekey:" + session, UUID.randomUUID() + ":1.0.0");

    assertThat(inputs.process(input(session, 1))).isEqualTo(GameInputService.Outcome.RETRY);

    assertThat(redis.opsForValue().get("routing:" + session))
        .isIn("input-host", "spare-host")
        .isNotEqualTo("down-host");
    behaviour = request -> ok(request, 0);
    assertThat(inputs.process(input(session, 1))).isEqualTo(GameInputService.Outcome.DELIVERED);
    registry.deregister("down-host");
  }

  private static ProcessResult okWithLog(ProcessInputRequest request) {
    return ProcessResult.newBuilder()
        .setSessionId(request.getSessionId())
        .setSequence(request.getSequence())
        .setStatus(ProcessStatus.PROCESS_STATUS_OK)
        .addEffects(
            Effect.newBuilder()
                .setLog(io.turnbase.grpc.gamehost.v1.LogEffect.newBuilder().setEntry("moved")))
        .build();
  }

  @Test
  void theWritesAfterAResultAreOneRedisRoundTrip() {
    behaviour = GameInputServiceIT::okWithLog;
    inputs.process(input(newSession(), 1)); // warms the script cache: the first call sends EVAL
    UUID session = newSession();
    String id = session.toString();
    COMMANDS.clear();

    assertThat(inputs.process(input(session, 1))).isEqualTo(GameInputService.Outcome.DELIVERED);

    // The client reports completed commands from its I/O thread, a moment after they return.
    long deadline = System.nanoTime() + 5_000_000_000L;
    while (COMMANDS.stream().noneMatch(c -> c.startsWith("EVAL") && c.contains(id))
        && System.nanoTime() < deadline) {
      Thread.onSpinWait();
    }
    List<String> ofSession = COMMANDS.stream().filter(c -> c.contains(id)).toList();
    List<String> fromDispatch = ofSession.subList(indexOfRoutingGet(ofSession), ofSession.size());
    assertThat(fromDispatch.stream().filter(c -> !c.startsWith("GET")))
        .as("every write after the result: the failcount reset, the log and the watermark")
        .hasSize(1)
        .allSatisfy(c -> assertThat(c).startsWith("EVAL").contains("failcount:", "gamelog:"));
    assertThat(redis.opsForValue().get("delivered:" + session)).isEqualTo("1");
    assertThat(redis.opsForValue().get("failcount:" + session)).isEqualTo("0");
    assertThat(redis.opsForList().range("gamelog:" + session, 0, -1)).hasSize(1);
  }

  @Test
  void theWatermarkIsNotAdvancedBeforeTheEffectsAreFannedOut() {
    behaviour = GameInputServiceIT::okWithLog;
    UUID session = newSession();
    redis.opsForValue().set("delivered:" + session, "6");

    inputs.process(input(session, 7));

    assertThat(WATERMARK_AT_DELIVERY.get(7L)).as("watermark during fan-out").isEqualTo("6");
    assertThat(redis.opsForValue().get("delivered:" + session)).isEqualTo("7");
  }

  @Test
  void aReplayedSequenceIsNeitherFannedOutNorLoggedAgain() {
    behaviour = GameInputServiceIT::okWithLog;
    UUID session = newSession();
    inputs.process(input(session, 1));
    int deliveries = DELIVERED.size();

    inputs.process(input(session, 1));

    assertThat(DELIVERED).hasSize(deliveries);
    assertThat(redis.opsForList().range("gamelog:" + session, 0, -1)).hasSize(1);
  }

  @Test
  void aFailedFanOutResetsTheFailureCountButLeavesTheWatermark() {
    behaviour = GameInputServiceIT::okWithLog;
    UUID session = newSession();
    redis.opsForValue().set("failcount:" + session, "2");
    FAIL_FANOUT.set(true);
    try {
      org.junit.jupiter.api.Assertions.assertThrows(
          IllegalStateException.class, () -> inputs.process(input(session, 1)));
    } finally {
      FAIL_FANOUT.set(false);
    }

    assertThat(redis.opsForValue().get("failcount:" + session)).isEqualTo("0");
    assertThat(redis.opsForValue().get("delivered:" + session)).isEqualTo("0");
    assertThat(redis.opsForList().range("gamelog:" + session, 0, -1)).isEmpty();
  }

  private static int indexOfRoutingGet(List<String> commands) {
    int index =
        commands.indexOf(
            commands.stream()
                .filter(c -> c.startsWith("GET key<routing:"))
                .findFirst()
                .orElseThrow());
    return index;
  }
}
