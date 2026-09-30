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
import io.turnbase.platform.TurnBaseApplication;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
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
class GameInputServiceIT extends PostgresIntegrationTest {

  record Delivered(UUID sessionId, long sequence, int effects) {}

  static final List<Delivered> DELIVERED = new CopyOnWriteArrayList<>();
  static final List<UUID> GAME_OVERS = new CopyOnWriteArrayList<>();

  @Configuration
  static class Recorder {
    @Bean
    @Primary
    EffectDelivery recordingDelivery() {
      return (sessionId, sequence, effects) ->
          DELIVERED.add(new Delivered(sessionId, sequence, effects.size()));
    }

    @Bean
    @Primary
    GameEndListener recordingGameEnd() {
      return GAME_OVERS::add;
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
        SpringApplication.run(
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
    redis = context.getBean(StringRedisTemplate.class);
    registry.register("input-host", "127.0.0.1:" + fakeHost.getPort(), List.of("jvm"));
  }

  @AfterAll
  static void shutdown() {
    fakeHost.shutdownNow();
    context.close();
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
      List<java.util.concurrent.Future<GameInputService.Outcome>> results =
          new java.util.ArrayList<>();
      for (int i = 0; i < 40; i++) {
        results.add(
            pool.submit(
                () ->
                    inputs.submitAction(
                        session, io.turnbase.grpc.gamehost.v1.ActionInput.getDefaultInstance())));
      }
      for (var result : results) {
        assertThat(result.get()).isEqualTo(GameInputService.Outcome.DELIVERED);
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
    assertThat(inputs.process(input(session, 3)))
        .isEqualTo(GameInputService.Outcome.RETRY_LIMIT_REACHED);
    assertThat(DELIVERED).noneMatch(d -> d.sessionId().equals(session));
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
}
