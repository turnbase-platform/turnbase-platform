package io.turnbase.platform.game;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.StreamObserver;
import io.turnbase.grpc.gamehost.v1.ContextKey;
import io.turnbase.grpc.gamehost.v1.DeregisterRequest;
import io.turnbase.grpc.gamehost.v1.GameHostRegistryServiceGrpc;
import io.turnbase.grpc.gamehost.v1.HeartbeatRequest;
import io.turnbase.grpc.gamehost.v1.HeartbeatResponse;
import io.turnbase.grpc.gamehost.v1.RegisterRequest;
import io.turnbase.grpc.gamehost.v1.RegisterResponse;
import io.turnbase.grpc.gamehost.v1.ReportContextsRequest;
import io.turnbase.platform.PostgresIntegrationTest;
import io.turnbase.platform.TurnBaseApplication;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * {@code IMP-M3-05}: {@code GameHostRegistryService} served on the monolith's own HTTP listener
 * (h2c through the servlet container, {@code local-environment.md} §7.8) and backed by the Redis
 * shape of {@code game-host-internals.md} §13 — exercised over a real socket by a real gRPC client,
 * including the bidirectional {@code Heartbeat} stream.
 */
class GameHostRegistryServerIT extends PostgresIntegrationTest {

  private static ConfigurableApplicationContext context;
  private static ManagedChannel channel;
  private static GameHostRegistryServiceGrpc.GameHostRegistryServiceBlockingStub blocking;
  private static StringRedisTemplate redis;
  private static HostRegistry registry;

  @BeforeAll
  static void boot() {
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
            "--spring.data.redis.url=" + redisUrl());
    int port = Integer.parseInt(context.getEnvironment().getProperty("local.server.port"));
    channel = NettyChannelBuilder.forAddress("127.0.0.1", port).usePlaintext().build();
    blocking = GameHostRegistryServiceGrpc.newBlockingStub(channel);
    redis = context.getBean(StringRedisTemplate.class);
    registry = context.getBean(HostRegistry.class);
  }

  @AfterAll
  static void shutdown() {
    channel.shutdownNow();
    context.close();
  }

  @Test
  void registerWritesTheRegistryKeysAndHandsBackTheLivenessTiming() {
    RegisterResponse response = register("host-a", "10.0.0.1:9090", "jvm");

    assertThat(response.getHeartbeatIntervalSeconds()).isEqualTo(5);
    assertThat(response.getLeaseTtlSeconds()).isEqualTo(15);
    assertThat(redis.opsForSet().isMember("hosts", "host-a")).isTrue();
    assertThat(redis.opsForHash().get("host:host-a", "address")).isEqualTo("10.0.0.1:9090");
    assertThat(redis.getExpire("hostlease:host-a", TimeUnit.SECONDS)).isBetween(1L, 15L);
    assertThat(registry.addressOf("host-a")).contains("10.0.0.1:9090");
  }

  @Test
  void aHostWhoseLeaseLapsedWithoutDeregisteringDropsOutOfTheLoadFigures() {
    register("host-lapsed", "10.0.0.9:9090", "jvm");
    assertThat(registry.loads()).anyMatch(l -> l.instanceId().equals("host-lapsed"));

    // A pod removed without a drain: its host: entry stays, only the lease expires.
    redis.delete("hostlease:host-lapsed");

    assertThat(redis.hasKey("host:host-lapsed")).isTrue();
    assertThat(registry.loads()).noneMatch(l -> l.instanceId().equals("host-lapsed"));
  }

  @Test
  void heartbeatStreamRefreshesTheLeaseAndCarriesLoadSignals() throws Exception {
    register("host-hb", "10.0.0.2:9090", "jvm");
    redis.expire("hostlease:host-hb", 2, TimeUnit.SECONDS);
    BlockingQueue<Object> responses = new LinkedBlockingQueue<>();
    StreamObserver<HeartbeatRequest> stream =
        GameHostRegistryServiceGrpc.newStub(channel)
            .heartbeat(
                new StreamObserver<>() {
                  public void onNext(HeartbeatResponse value) {
                    responses.add(value);
                  }

                  public void onError(Throwable t) {
                    responses.add(t);
                  }

                  public void onCompleted() {}
                });

    stream.onNext(
        HeartbeatRequest.newBuilder()
            .setInstanceId("host-hb")
            .setActiveSessions(7)
            .setActiveContexts(2)
            .setMemoryUsedPercent(41)
            .build());

    assertThat(responses.poll(5, TimeUnit.SECONDS))
        .isEqualTo(HeartbeatResponse.getDefaultInstance());
    assertThat(redis.getExpire("hostlease:host-hb", TimeUnit.SECONDS)).isGreaterThan(2L);
    assertThat(redis.opsForHash().get("host:host-hb", "activeSessions")).isEqualTo("7");

    registry.drain("host-hb");
    stream.onNext(HeartbeatRequest.newBuilder().setInstanceId("host-hb").build());
    HeartbeatResponse drain = (HeartbeatResponse) responses.poll(5, TimeUnit.SECONDS);
    assertThat(drain).isNotNull();
    assertThat(drain.getDrain()).isTrue();
    stream.onCompleted();
  }

  @Test
  void heartbeatFromAnUnregisteredInstanceIsNotFound() throws Exception {
    BlockingQueue<Object> responses = new LinkedBlockingQueue<>();
    StreamObserver<HeartbeatRequest> stream =
        GameHostRegistryServiceGrpc.newStub(channel)
            .heartbeat(
                new StreamObserver<>() {
                  public void onNext(HeartbeatResponse value) {
                    responses.add(value);
                  }

                  public void onError(Throwable t) {
                    responses.add(t);
                  }

                  public void onCompleted() {}
                });
    stream.onNext(HeartbeatRequest.newBuilder().setInstanceId("never-registered").build());

    assertThat(responses.poll(5, TimeUnit.SECONDS))
        .isEqualTo(HeartbeatResponse.getDefaultInstance());
    Object outcome = responses.poll(5, TimeUnit.SECONDS);
    assertThat(outcome).isInstanceOf(StatusRuntimeException.class);
    assertThat(Status.fromThrowable((Throwable) outcome).toString()).contains("NOT_FOUND");
  }

  @Test
  void selectionKeepsLiveNonDrainingJvmHostsPrefersWarmThenLeastLoadedThenLexical() {
    register("sel-c", "c:1", "jvm");
    register("sel-b", "b:1", "jvm");
    register("sel-a", "a:1", "jvm");
    register("sel-wasm", "w:1", "wasm");
    register("sel-drain", "d:1", "jvm");
    registry.drain("sel-drain");
    for (String other : List.of("host-a", "host-hb")) {
      registry.deregister(other);
    }

    // No warm host: lowest load, then lexical instanceId.
    registry.heartbeat("sel-a", 3, 1, 0);
    registry.heartbeat("sel-b", 1, 1, 0);
    registry.heartbeat("sel-c", 1, 1, 0);
    assertThat(registry.selectHost("game", "1.0.0")).contains("sel-b");

    // One warm host is fewer than warm-hosts (2): the least loaded cold one is chosen to warm it.
    blocking.reportContexts(
        ReportContextsRequest.newBuilder()
            .setInstanceId("sel-a")
            .addContexts(ContextKey.newBuilder().setGameId("game").setGameVersion("1.0.0"))
            .build());
    assertThat(registry.selectHost("game", "1.0.0")).contains("sel-b");
    assertThat(redis.opsForSet().members("contexts:sel-a")).containsExactly("game:1.0.0");

    // Two warm hosts: warm beats the less loaded cold one, then the least loaded of the warm.
    blocking.reportContexts(
        ReportContextsRequest.newBuilder()
            .setInstanceId("sel-c")
            .addContexts(ContextKey.newBuilder().setGameId("game").setGameVersion("1.0.0"))
            .build());
    registry.heartbeat("sel-b", 0, 1, 0);
    assertThat(registry.selectHost("game", "1.0.0")).contains("sel-c");

    // A full snapshot replaces, never merges.
    blocking.reportContexts(ReportContextsRequest.newBuilder().setInstanceId("sel-a").build());
    assertThat(redis.hasKey("contexts:sel-a")).isFalse();

    // A lease that has lapsed removes the host from selection; nothing left is empty.
    for (String id : List.of("sel-a", "sel-b", "sel-c")) {
      redis.delete("hostlease:" + id);
    }
    assertThat(registry.selectHost("game", "1.0.0")).isEqualTo(Optional.empty());
  }

  @Test
  void selectionSkipsQuarantinedHostsAndHostsAtTheSessionCap() {
    for (String other : List.of("host-a", "host-hb")) {
      registry.deregister(other);
    }
    register("cap-a", "a:1", "jvm");
    register("cap-b", "b:1", "jvm");
    register("cap-q", "q:1", "jvm");
    blocking.reportContexts(
        ReportContextsRequest.newBuilder()
            .setInstanceId("cap-a")
            .addContexts(ContextKey.newBuilder().setGameId("game").setGameVersion("1.0.0"))
            .build());
    registry.heartbeat("cap-q", 0, 0, 0);
    registry.quarantine("cap-q");

    // Warm cap-a is the only warm host (warm-hosts is 2): the cold cap-b is warmed first.
    registry.heartbeat("cap-a", 99, 1, 0);
    assertThat(registry.selectHost("game", "1.0.0")).contains("cap-b");

    // The warm host reaches the cap (default 100): the cold one takes the session.
    registry.heartbeat("cap-a", 100, 1, 0);
    assertThat(registry.selectHost("game", "1.0.0")).contains("cap-b");

    // Every routable host at the cap (the quarantined one is idle but skipped).
    registry.heartbeat("cap-b", 100, 1, 0);
    assertThat(registry.selectHost("game", "1.0.0")).isEqualTo(Optional.empty());

    for (String id : List.of("cap-a", "cap-b", "cap-q")) {
      registry.deregister(id);
      redis.delete("hostquarantine:" + id);
    }
  }

  @Test
  void aDrainingHeartbeatTakesTheHostOutOfSelectionAndTheScalingGauges() {
    for (String other : redis.opsForSet().members("hosts")) {
      registry.deregister(other);
    }
    register("dr-a", "a:1", "jvm");
    register("dr-b", "b:1", "jvm");
    registry.heartbeat("dr-a", 30, 1, 40);
    registry.heartbeat("dr-b", 10, 1, 60);
    var meters = context.getBean(io.micrometer.core.instrument.MeterRegistry.class);
    assertThat(meters.get("turnbase.gamehost.live").gauge().value()).isEqualTo(2);
    assertThat(meters.get("turnbase.gamehost.sessions.active").gauge().value()).isEqualTo(40);
    assertThat(meters.get("turnbase.gamehost.sessions.utilization").gauge().value())
        .isEqualTo(40.0 / 200);
    assertThat(meters.get("turnbase.gamehost.memory.max.percent").gauge().value()).isEqualTo(60);

    registry.heartbeat("dr-a", 30, 1, 40, true);
    // a later beat without the flag does not undo it
    registry.heartbeat("dr-a", 30, 1, 40, false);

    assertThat(registry.isDraining("dr-a")).isTrue();
    assertThat(registry.selectHost("game", "1.0.0")).contains("dr-b");
    assertThat(meters.get("turnbase.gamehost.live").gauge().value()).isEqualTo(1);
    // the draining host's sessions leave the numerator as well as the capacity
    assertThat(meters.get("turnbase.gamehost.sessions.utilization").gauge().value())
        .isEqualTo(10.0 / 100);

    registry.deregister("dr-a");
    registry.deregister("dr-b");
  }

  @Test
  void deregisterRemovesEveryKeyAndIsIdempotent() {
    register("host-gone", "g:1", "jvm");
    blocking.deregister(DeregisterRequest.newBuilder().setInstanceId("host-gone").build());
    blocking.deregister(DeregisterRequest.newBuilder().setInstanceId("host-gone").build());

    assertThat(redis.opsForSet().isMember("hosts", "host-gone")).isFalse();
    assertThat(redis.hasKey("host:host-gone")).isFalse();
    assertThat(redis.hasKey("hostlease:host-gone")).isFalse();
  }

  /**
   * Known limit of serving gRPC through Tomcat: a call that fails before it sends any message is a
   * trailers-only response, and its status does not survive the transport — the client sees {@code
   * INTERNAL}. The call still fails, which is all a malformed {@code Register} needs.
   */
  @Test
  void aMalformedRegisterFails() {
    assertThatThrownBy(() -> register("", "", "jvm")).isInstanceOf(StatusRuntimeException.class);
  }

  private static RegisterResponse register(String id, String address, String runtime) {
    return blocking.register(
        RegisterRequest.newBuilder()
            .setInstanceId(id)
            .setAddress(address)
            .addRuntimeTypes(runtime)
            .build());
  }
}
