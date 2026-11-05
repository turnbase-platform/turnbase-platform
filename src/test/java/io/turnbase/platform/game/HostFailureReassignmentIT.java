package io.turnbase.platform.game;

import static org.assertj.core.api.Assertions.assertThat;

import io.grpc.Server;
import io.grpc.Status;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import io.turnbase.grpc.gamehost.v1.Effect;
import io.turnbase.grpc.gamehost.v1.GameHostServiceGrpc;
import io.turnbase.grpc.gamehost.v1.ProcessInputRequest;
import io.turnbase.grpc.gamehost.v1.ProcessInputResponse;
import io.turnbase.grpc.gamehost.v1.ProcessResult;
import io.turnbase.grpc.gamehost.v1.ProcessStatus;
import io.turnbase.platform.PostgresIntegrationTest;
import io.turnbase.platform.TurnBaseApplication;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * {@code IMP-M18-12}: the {@code OQ-5} correctness suite. Several in-process Game Hosts that model
 * the host's contract (a sequence at or below {@code lastAppliedSequence} is a replay; the session
 * record outlives the host) are killed, drained or revived under the real dispatch path, and the
 * same scenarios run against each reassignment mode. Results are written to {@code
 * target/m18-reassignment-results.md}; the report quotes them.
 *
 * <p>Requirements protected: {@code platform-architecture.md} FR-FAIL-002, FR-FAIL-003,
 * NFR-REL-002.
 */
class HostFailureReassignmentIT extends PostgresIntegrationTest {

  /**
   * The mode under test. The M18-12 measurement ran {@code lazy} and {@code eager} through this
   * suite (report: {@code docs/reviews/2026-10-29-m18-reassignment/INDEX.md}); lazy won and eager's
   * code and switch were removed.
   */
  static final List<String> MODES = List.of("lazy");

  private static final String GAME_KEY = UUID.randomUUID() + ":tictactoe";

  record Delivered(UUID sessionId, long sequence) {}

  record Applied(UUID sessionId, long sequence, String host) {}

  static final List<Delivered> DELIVERED = new CopyOnWriteArrayList<>();
  static final List<UUID> ABORTED = new CopyOnWriteArrayList<>();
  static final List<Applied> APPLIED = new CopyOnWriteArrayList<>();

  /** Stands for {@code session:{id}.lastAppliedSequence}, which lives in Redis in production. */
  static final Map<UUID, AtomicLong> LAST_APPLIED = new ConcurrentHashMap<>();

  /**
   * Registered programmatically, not as a nested {@code @Configuration}: component scanning would
   * pull a nested one into every other IT in this package (and {@code GameInputServiceIT}'s own
   * recorder, scanned into this context, is overridden by the same bean names).
   */
  static void recorders(org.springframework.context.support.GenericApplicationContext context) {
    context.registerBean(
        "recordingDelivery",
        EffectDelivery.class,
        () ->
            (sessionId, sequence, effects) -> {
              DELIVERED.add(new Delivered(sessionId, sequence));
              return List.of();
            },
        bd -> bd.setPrimary(true));
    context.registerBean(
        "recordingGameEnd",
        GameEndListener.class,
        () -> sessionId -> {},
        bd -> bd.setPrimary(true));
    context.registerBean(
        "recordingSessionFailure",
        SessionFailureListener.class,
        () -> ABORTED::add,
        bd -> bd.setPrimary(true));
  }

  enum Fault {
    NONE,
    DIE_BEFORE_APPLY,
    DIE_AFTER_APPLY
  }

  /** One fake Game Host: a gRPC server plus a heartbeat that can be stopped (lease expiry). */
  static final class Host {
    final String id;
    final Server server;
    volatile boolean beating = true;
    volatile Fault fault = Fault.NONE;
    final AtomicInteger calls = new AtomicInteger();

    Host(String id) throws Exception {
      this.id = id;
      this.server =
          NettyServerBuilder.forPort(0)
              .addService(
                  new GameHostServiceGrpc.GameHostServiceImplBase() {
                    @Override
                    public void processInput(
                        ProcessInputRequest request, StreamObserver<ProcessInputResponse> out) {
                      calls.incrementAndGet();
                      handle(request, out);
                    }
                  })
              .build()
              .start();
    }

    void kill() {
      beating = false;
      server.shutdownNow();
    }

    private void handle(ProcessInputRequest request, StreamObserver<ProcessInputResponse> out) {
      UUID session = UUID.fromString(request.getSessionId());
      if (fault == Fault.DIE_BEFORE_APPLY) {
        fault = Fault.NONE;
        kill();
        out.onError(Status.UNAVAILABLE.asRuntimeException());
        return;
      }
      AtomicLong last = LAST_APPLIED.computeIfAbsent(session, s -> new AtomicLong());
      synchronized (last) {
        if (request.getSequence() > last.get()) {
          APPLIED.add(new Applied(session, request.getSequence(), id));
          last.set(request.getSequence());
        }
      }
      if (fault == Fault.DIE_AFTER_APPLY) {
        fault = Fault.NONE;
        kill();
        out.onError(Status.UNAVAILABLE.asRuntimeException());
        return;
      }
      out.onNext(
          ProcessInputResponse.newBuilder()
              .setResult(
                  ProcessResult.newBuilder()
                      .setSessionId(request.getSessionId())
                      .setSequence(request.getSequence())
                      .setStatus(ProcessStatus.PROCESS_STATUS_OK)
                      .addEffects(Effect.getDefaultInstance()))
              .build());
      out.onCompleted();
    }
  }

  /** What one scenario measured in one mode. */
  record Outcome(
      String scenario, List<String> failures, long recoveryMs, int grpcCalls, long redis) {}

  /** The scenario's view of the running system. */
  static final class Env {
    final GameInputService inputs;
    final HostRegistry registry;
    final StringRedisTemplate redis;
    final List<Host> hosts = new ArrayList<>();
    final List<UUID> sessions = new ArrayList<>();
    final Map<UUID, Object> dispatchLocks = new ConcurrentHashMap<>();
    long killedAtNanos;

    Env(GameInputService inputs, HostRegistry registry, StringRedisTemplate redis) {
      this.inputs = inputs;
      this.registry = registry;
      this.redis = redis;
    }

    Host host(String id) throws Exception {
      Host host = new Host(id);
      hosts.add(host);
      registry.register(id, "127.0.0.1:" + host.server.getPort(), List.of("jvm"));
      return host;
    }

    UUID session(Host on) {
      UUID id = UUID.randomUUID();
      sessions.add(id);
      redis.opsForValue().set("routing:" + id, on.id);
      redis.opsForValue().set("gamekey:" + id, GAME_KEY);
      redis.opsForValue().set("seq:" + id, "0");
      redis.opsForValue().set("delivered:" + id, "0");
      redis.opsForValue().set("failcount:" + id, "0");
      return id;
    }

    /**
     * The session's serial dispatcher: one sequence, re-dispatched until the platform accepts it
     * (the head-of-queue rule), serial per session. Returns false when the session was aborted.
     */
    boolean dispatch(UUID session) {
      synchronized (dispatchLocks.computeIfAbsent(session, s -> new Object())) {
        long sequence = inputs.allocateSequence(session);
        ProcessInputRequest request =
            ProcessInputRequest.newBuilder()
                .setSessionId(session.toString())
                .setSequence(sequence)
                .build();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
          GameInputService.Outcome outcome = inputs.process(request);
          if (outcome == GameInputService.Outcome.DELIVERED) {
            return true;
          }
          if (outcome != GameInputService.Outcome.RETRY) {
            return false;
          }
          sleep(100);
        }
        return false;
      }
    }

    void kill(Host host) {
      killedAtNanos = System.nanoTime();
      host.kill();
    }

    long msSinceKill() {
      return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - killedAtNanos);
    }

    /** The correctness checks of {@code load-testing-phase-b.md} §5.2, per session. */
    List<String> check(UUID session, int expectedInputs) {
      List<String> failures = new ArrayList<>();
      List<Long> delivered =
          DELIVERED.stream()
              .filter(d -> d.sessionId().equals(session))
              .map(Delivered::sequence)
              .toList();
      List<Long> expected = new ArrayList<>();
      for (long i = 1; i <= expectedInputs; i++) {
        expected.add(i);
      }
      if (!delivered.equals(expected)) {
        failures.add("delivered " + delivered + ", expected " + expected + " (lost/twice/order)");
      }
      List<Long> applied =
          APPLIED.stream()
              .filter(a -> a.sessionId().equals(session))
              .map(Applied::sequence)
              .toList();
      if (!applied.equals(expected)) {
        failures.add("applied " + applied + ", expected " + expected + " (lost/twice/order)");
      }
      String routed = redis.opsForValue().get("routing:" + session);
      if (routed == null || !registry.isLive(routed)) {
        failures.add("still routed to dead host " + routed);
      }
      if (ABORTED.contains(session)) {
        failures.add("game aborted");
      }
      return failures;
    }

    void cleanup() {
      hosts.forEach(
          h -> {
            h.kill();
            registry.deregister(h.id);
          });
      sessions.forEach(
          s ->
              redis.delete(
                  List.of(
                      "routing:" + s,
                      "gamekey:" + s,
                      "seq:" + s,
                      "delivered:" + s,
                      "failcount:" + s)));
    }
  }

  @FunctionalInterface
  interface Scenario {
    /** Runs against a fresh {@link Env}; returns the failures and sets nothing else. */
    List<String> run(Env env) throws Exception;
  }

  static void sleep(long ms) {
    try {
      Thread.sleep(ms);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private static Scenario idleKillThenInputAfter(long waitMs) {
    return env -> {
      Host a = env.host("a");
      env.host("b");
      UUID session = env.session(a);
      assertThat(env.dispatch(session)).isTrue();
      env.kill(a);
      sleep(waitMs);
      boolean ok = env.dispatch(session);
      List<String> failures = new ArrayList<>(env.check(session, 2));
      if (!ok) {
        failures.add("next input not accepted");
      }
      return failures;
    };
  }

  private static Scenario killMidMove(Fault fault) {
    return env -> {
      Host a = env.host("a");
      env.host("b");
      UUID session = env.session(a);
      assertThat(env.dispatch(session)).isTrue();
      a.fault = fault;
      env.killedAtNanos = System.nanoTime();
      boolean ok = env.dispatch(session);
      List<String> failures = new ArrayList<>(env.check(session, 2));
      if (!ok) {
        failures.add("in-flight input not accepted");
      }
      return failures;
    };
  }

  static final Map<String, Scenario> SCENARIOS = new java.util.LinkedHashMap<>();

  static {
    SCENARIOS.put("killed mid-move, input not applied", killMidMove(Fault.DIE_BEFORE_APPLY));
    SCENARIOS.put("killed mid-move, input applied", killMidMove(Fault.DIE_AFTER_APPLY));
    SCENARIOS.put("idle kill, next input after 1 s", idleKillThenInputAfter(1_000));
    SCENARIOS.put("idle kill, next input after the lease TTL", idleKillThenInputAfter(2_000));
    SCENARIOS.put("idle kill, next input after reassignment", idleKillThenInputAfter(5_000));
    SCENARIOS.put(
        "kill with a timer pending (timer input after the TTL)", idleKillThenInputAfter(2_500));
    SCENARIOS.put(
        "zombie: lease expired, host still answers, then returns",
        env -> {
          Host a = env.host("a");
          Host b = env.host("b");
          UUID session = env.session(a);
          assertThat(env.dispatch(session)).isTrue();
          a.beating = false; // the lease lapses; the server keeps answering gRPC
          sleep(2_500);
          boolean first = env.dispatch(session);
          a.beating = true;
          env.registry.heartbeat(a.id, 0, 0, 0);
          boolean second = env.dispatch(session);
          sleep(1_000);
          boolean third = env.dispatch(session);
          List<String> failures = new ArrayList<>(env.check(session, 4));
          if (!(first && second && third)) {
            failures.add("an input was not accepted");
          }
          return failures;
        });
    SCENARIOS.put(
        "two hosts killed in succession",
        env -> {
          Host a = env.host("a");
          Host b = env.host("b");
          env.host("c");
          UUID session = env.session(a);
          assertThat(env.dispatch(session)).isTrue();
          env.kill(a);
          boolean second = env.dispatch(session);
          Host now =
              env.hosts.stream()
                  .filter(h -> h.id.equals(env.redis.opsForValue().get("routing:" + session)))
                  .findFirst()
                  .orElseThrow();
          env.kill(now);
          boolean third = env.dispatch(session);
          List<String> failures = new ArrayList<>(env.check(session, 3));
          if (!(second && third)) {
            failures.add("an input was not accepted");
          }
          return failures;
        });
    SCENARIOS.put(
        "planned drain, then the host leaves",
        env -> {
          Host a = env.host("a");
          env.host("b");
          UUID session = env.session(a);
          assertThat(env.dispatch(session)).isTrue();
          env.registry.drain(a.id);
          boolean during = env.dispatch(session); // draining hosts keep serving their sessions
          env.killedAtNanos = System.nanoTime();
          a.kill();
          env.registry.deregister(a.id);
          sleep(1_500);
          boolean after = env.dispatch(session);
          List<String> failures = new ArrayList<>(env.check(session, 3));
          if (!(during && after)) {
            failures.add("an input was not accepted");
          }
          return failures;
        });
    SCENARIOS.put(
        "concurrent inputs for one session during reassignment",
        env -> {
          Host a = env.host("a");
          env.host("b");
          UUID session = env.session(a);
          assertThat(env.dispatch(session)).isTrue();
          env.kill(a);
          ExecutorService pool = Executors.newFixedThreadPool(8);
          List<Future<Boolean>> results = new ArrayList<>();
          for (int i = 0; i < 8; i++) {
            results.add(pool.submit(() -> env.dispatch(session)));
          }
          List<String> failures = new ArrayList<>();
          for (Future<Boolean> result : results) {
            if (!result.get()) {
              failures.add("an input was not accepted");
            }
          }
          pool.shutdownNow();
          failures.addAll(env.check(session, 9));
          return failures;
        });
  }

  private ConfigurableApplicationContext boot() {
    SpringApplication application = new SpringApplication(TurnBaseApplication.class);
    application.setAllowBeanDefinitionOverriding(true);
    application.addInitializers(
        c -> recorders((org.springframework.context.support.GenericApplicationContext) c));
    return application.run(
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
          "--turnbase.orchestration.heartbeat-interval=500ms",
          "--turnbase.orchestration.lease-ttl=1500ms",
          "--turnbase.orchestration.dispatch-deadline=2s"
        });
  }

  private static long redisCommands(StringRedisTemplate redis) {
    String info =
        redis.execute(
            (org.springframework.data.redis.core.RedisCallback<String>)
                c -> c.serverCommands().info("stats").getProperty("total_commands_processed"));
    return Long.parseLong(info.trim());
  }

  @Test
  void bothModesAreMeasuredAgainstTheSameScenarios() throws Exception {
    List<Outcome> all = new ArrayList<>();
    for (String mode : MODES) {
      ConfigurableApplicationContext context = boot();
      ScheduledExecutorService beats = Executors.newSingleThreadScheduledExecutor();
      try {
        GameInputService inputs = context.getBean(GameInputService.class);
        HostRegistry registry = context.getBean(HostRegistry.class);
        StringRedisTemplate redis = context.getBean(StringRedisTemplate.class);
        for (Map.Entry<String, Scenario> entry : SCENARIOS.entrySet()) {
          Env env = new Env(inputs, registry, redis);
          beats.scheduleWithFixedDelay(
              () ->
                  env.hosts.stream()
                      .filter(h -> h.beating)
                      .forEach(h -> registry.heartbeat(h.id, 0, 0, 0)),
              0,
              500,
              TimeUnit.MILLISECONDS);
          long redisBefore = redisCommands(redis);
          long started = System.nanoTime();
          List<String> failures = new ArrayList<>();
          try {
            failures.addAll(entry.getValue().run(env));
          } catch (Throwable t) {
            failures.add("scenario threw " + t);
          }
          long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
          long redisUsed = redisCommands(redis) - redisBefore;
          int calls = env.hosts.stream().mapToInt(h -> h.calls.get()).sum();
          long recovery = env.killedAtNanos == 0 ? elapsed : env.msSinceKill();
          all.add(new Outcome(mode + " | " + entry.getKey(), failures, recovery, calls, redisUsed));
          env.cleanup();
          beats.shutdown();
          beats = Executors.newSingleThreadScheduledExecutor();
        }
      } finally {
        beats.shutdownNow();
        context.close();
      }
    }
    StringBuilder table =
        new StringBuilder(
                "| mode | scenario | failures | kill→done ms | gRPC calls | Redis cmds |\n")
            .append("|---|---|---|---|---|---|\n");
    for (Outcome o : all) {
      String[] parts = o.scenario().split(" \\| ", 2);
      table.append(
          "| %s | %s | %s | %d | %d | %d |%n"
              .formatted(
                  parts[0],
                  parts[1],
                  o.failures().isEmpty() ? "0" : o.failures().size() + ": " + o.failures(),
                  o.recoveryMs(),
                  o.grpcCalls(),
                  o.redis()));
    }
    Path out = Path.of("target/m18-reassignment-results.md");
    Files.createDirectories(out.getParent());
    Files.writeString(out, table.toString());
    System.out.println(table);
    assertThat(all).hasSize(MODES.size() * SCENARIOS.size());
    assertThat(all).allSatisfy(o -> assertThat(o.failures()).as(o.scenario()).isEmpty());
  }
}
