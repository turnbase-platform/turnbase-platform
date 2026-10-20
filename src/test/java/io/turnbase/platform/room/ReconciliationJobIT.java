package io.turnbase.platform.room;

import static org.assertj.core.api.Assertions.assertThat;

import io.turnbase.platform.PostgresIntegrationTest;
import io.turnbase.platform.TurnBaseApplication;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@code IMP-M2-10}'s own Done-when: all five FR-JOB-007 reconciliation duties, each idempotent
 * (FR-JOB-010) and taking exactly the FR-JOB-008 guards `room-lifecycle.md` §5.4 assigns it — the
 * two-strike rule for (b)/(c)/(d), none for (e). Startup suppression is exercised by overriding
 * {@code turnbase.jobs.reconcile.startup-suppression} to a window this test can control rather than
 * waiting out the real 120s default.
 */
class ReconciliationJobIT extends PostgresIntegrationTest {

  private @Nullable ConfigurableApplicationContext context;

  @AfterEach
  void stopApplication() {
    if (context != null) {
      context.close();
    }
  }

  @Test
  void dutyARedrivesAnOwedTeardownAndBacksOffOnFailure() {
    context = boot("0s");
    ReconciliationJob job = context.getBean(ReconciliationJob.class);
    UUID roomId = insertRoom(context, "LOBBY");
    UUID sessionId = UUID.randomUUID();
    insertDueTeardown(context, sessionId, roomId);
    // The routed host is registered but unreachable, so the teardown cannot be confirmed.
    context
        .getBean(org.springframework.data.redis.core.StringRedisTemplate.class)
        .opsForHash()
        .put("host:dead-host", "address", "127.0.0.1:1");
    context
        .getBean(org.springframework.data.redis.core.StringRedisTemplate.class)
        .opsForValue()
        .set("routing:" + sessionId, "dead-host");

    job.run();

    Map<String, Object> row = teardownRow(context, sessionId);
    assertThat(((Number) Objects.requireNonNull(row.get("attempts"))).intValue()).isEqualTo(1);
    assertThat(row.get("last_error")).isNotNull();
    // An unconfirmed terminate backs off rather than clearing the row (room-lifecycle.md §4 (c)).
    Instant nextAttemptAt =
        ((Timestamp) Objects.requireNonNull(row.get("next_attempt_at"))).toInstant();
    assertThat(nextAttemptAt).isAfter(Instant.now());
  }

  @Test
  void dutyFSweepRemovesTheOwnershipKeysOfClosedAndMissingRoomsAndLeavesOpenOnes_IMP_M16_08() {
    context = boot("0s");
    ReconciliationJob job = context.getBean(ReconciliationJob.class);
    StringRedisTemplate redis = context.getBean(StringRedisTemplate.class);
    UUID openRoom = insertRoom(context, "LOBBY");
    UUID inProgressRoom = insertRoom(context, "IN_PROGRESS");
    UUID closedRoom = insertRoom(context, "CLOSED");
    redis.opsForValue().set("room:" + openRoom + ":pod", "pod-x");
    redis.opsForValue().set("room:" + openRoom + ":epoch", "3");
    redis.opsForValue().set("room:" + inProgressRoom + ":pod", "pod-x");
    redis.opsForValue().set("room:" + inProgressRoom + ":epoch", "3");
    redis.opsForValue().set("room:" + closedRoom + ":pod", "pod-x");
    redis.opsForValue().set("room:" + closedRoom + ":epoch", "3");
    redis.opsForValue().set("room:not-a-uuid:pod", "left alone");
    List<UUID> orphans = new ArrayList<>();
    for (int i = 0; i < 5_000; i++) {
      UUID orphan = i % 2 == 0 ? UUID.randomUUID() : insertRoom(context, "CLOSED");
      orphans.add(orphan);
      redis.opsForValue().set("room:" + orphan + ":pod", "pod-x");
      redis.opsForValue().set("room:" + orphan + ":epoch", "7");
    }
    RoomOwnershipServiceIT.resetRedisStats(redis);

    job.sweepClosedRoomKeys();

    for (UUID orphan : orphans) {
      assertThat(redis.hasKey("room:" + orphan + ":pod")).isFalse();
      assertThat(redis.hasKey("room:" + orphan + ":epoch")).isFalse();
    }
    assertThat(redis.hasKey("room:" + closedRoom + ":pod")).isFalse();
    assertThat(redis.hasKey("room:" + closedRoom + ":epoch")).isFalse();
    for (UUID open : List.of(openRoom, inProgressRoom)) {
      assertThat(redis.opsForValue().get("room:" + open + ":pod")).isEqualTo("pod-x");
      assertThat(redis.opsForValue().get("room:" + open + ":epoch")).isEqualTo("3");
    }
    assertThat(redis.hasKey("room:not-a-uuid:pod")).isTrue();
    assertThat(RoomOwnershipServiceIT.redisCommandCalls(redis, "keys")).isZero();
  }

  @Test
  void dutyBWalksTheKeyspaceOnceWithScanAndCleansExactlyTheStaleKeys_IMP_M16_09() {
    context = boot("0s");
    ReconciliationJob job = context.getBean(ReconciliationJob.class);
    StringRedisTemplate redis = context.getBean(StringRedisTemplate.class);
    JdbcClient jdbc = context.getBean(JdbcClient.class);
    cleanSlate(context);
    Map<String, String> keys = new HashMap<>();
    List<UUID> stale = new ArrayList<>();
    for (int i = 0; i < 5_000; i++) {
      UUID sessionId = UUID.randomUUID();
      stale.add(sessionId);
      keys.put("session:" + sessionId, "x");
      keys.put("routing:" + sessionId, "x");
    }
    List<UUID> live = new ArrayList<>();
    UUID gameId = insertGame(jdbc);
    for (int i = 0; i < 100; i++) {
      UUID sessionId = UUID.randomUUID();
      live.add(sessionId);
      UUID roomId = insertRoomOfGame(jdbc, gameId, "IN_PROGRESS");
      setActiveSession(context, roomId, sessionId);
      keys.put("session:" + sessionId, "x");
      keys.put("gamelog:" + sessionId, "x");
    }
    keys.put("session:not-a-uuid", "left alone");
    keys.put("room:" + UUID.randomUUID() + ":pod", "left alone");
    keys.put("unrelated", "left alone");
    mset(redis, keys);

    // What one full SCAN iteration costs on this keyspace, measured by a plain walk.
    RoomOwnershipServiceIT.resetRedisStats(redis);
    try (var cursor = redis.scan(ScanOptions.scanOptions().count(500).build())) {
      cursor.forEachRemaining(key -> {});
    }
    long onePass = RoomOwnershipServiceIT.redisCommandCalls(redis, "scan");
    assertThat(onePass).isGreaterThan(10);

    RoomOwnershipServiceIT.resetRedisStats(redis);
    job.reconcileOrphanedSessionArtifacts(false);
    assertThat(RoomOwnershipServiceIT.redisCommandCalls(redis, "keys")).isZero();
    assertThat(RoomOwnershipServiceIT.redisCommandCalls(redis, "scan")).isBetween(1L, onePass + 2);
    assertThat(redis.hasKey("session:" + stale.get(0))).isTrue(); // first observation only
    assertThat(orphanedSessionFlagCount(jdbc)).isEqualTo(5_000);

    RoomOwnershipServiceIT.resetRedisStats(redis);
    job.reconcileOrphanedSessionArtifacts(false);
    assertThat(RoomOwnershipServiceIT.redisCommandCalls(redis, "keys")).isZero();
    assertThat(RoomOwnershipServiceIT.redisCommandCalls(redis, "scan")).isBetween(1L, onePass + 2);
    for (UUID sessionId : stale) {
      assertThat(redis.hasKey("session:" + sessionId)).isFalse();
      assertThat(redis.hasKey("routing:" + sessionId)).isFalse();
    }
    for (UUID sessionId : live) {
      assertThat(redis.hasKey("session:" + sessionId)).isTrue();
      assertThat(redis.hasKey("gamelog:" + sessionId)).isTrue();
    }
    assertThat(redis.hasKey("session:not-a-uuid")).isTrue();
    assertThat(redis.hasKey("unrelated")).isTrue();
    assertThat(orphanedSessionFlagCount(jdbc)).isZero();
  }

  @Test
  void aTickIssuesTheSameNumberOfStatementsWhateverTheNumberOfSessions_IMP_M16_09() {
    context = boot("0s", StatementCounter.class);
    ReconciliationJob job = context.getBean(ReconciliationJob.class);
    StatementCounter counter = context.getBean(StatementCounter.class);
    cleanSlate(context);

    seedSessions(context, 20, 20);
    long smallFirst = counter.count(job::run);
    long smallSecond = counter.count(job::run);

    seedSessions(context, 200, 200);
    long largeFirst = counter.count(job::run);
    long largeSecond = counter.count(job::run);

    assertThat(smallFirst).isBetween(4L, 40L);
    assertThat(smallSecond).isBetween(4L, 40L);
    assertThat(largeFirst).isEqualTo(smallFirst);
    assertThat(largeSecond).isEqualTo(smallSecond);
  }

  /**
   * {@code orphans} sessions with Redis keys and no room, and {@code healthy} In Progress rooms
   * whose session record is present, half of them carrying a stale reconcile flag and a stale
   * orphan flag.
   */
  private static void seedSessions(
      ConfigurableApplicationContext context, int orphans, int healthy) {
    JdbcClient jdbc = context.getBean(JdbcClient.class);
    StringRedisTemplate redis = context.getBean(StringRedisTemplate.class);
    UUID gameId = insertGame(jdbc);
    Map<String, String> keys = new HashMap<>();
    for (int i = 0; i < orphans; i++) {
      keys.put("session:" + UUID.randomUUID(), "x");
    }
    for (int i = 0; i < healthy; i++) {
      UUID sessionId = UUID.randomUUID();
      UUID roomId = insertRoomOfGame(jdbc, gameId, "IN_PROGRESS");
      setActiveSession(context, roomId, sessionId);
      keys.put("session:" + sessionId, "x");
      if (i % 2 == 0) {
        jdbc.sql("update room set reconcile_flagged_at = now() where room_id = :roomId")
            .param("roomId", roomId)
            .update();
        jdbc.sql("insert into orphaned_session_flag (session_id) values (:sessionId)")
            .param("sessionId", sessionId)
            .update();
      }
    }
    mset(redis, keys);
  }

  /** The containers outlive each test's context: start from the keys and flags of nobody. */
  private static void cleanSlate(ConfigurableApplicationContext context) {
    StringRedisTemplate redis = context.getBean(StringRedisTemplate.class);
    redis.execute(
        (RedisCallback<String>)
            connection -> {
              connection.serverCommands().flushAll();
              return "OK";
            });
    context.getBean(JdbcClient.class).sql("delete from orphaned_session_flag").update();
    context
        .getBean(JdbcClient.class)
        .sql("update room set state = 'CLOSED', closed_at = now() where state <> 'CLOSED'")
        .update();
  }

  private static void mset(StringRedisTemplate redis, Map<String, String> keys) {
    List<String> names = new ArrayList<>(keys.keySet());
    for (int from = 0; from < names.size(); from += 1_000) {
      Map<String, String> chunk = new HashMap<>();
      for (String name : names.subList(from, Math.min(from + 1_000, names.size()))) {
        chunk.put(name, keys.get(name));
      }
      redis.opsForValue().multiSet(chunk);
    }
  }

  private static long orphanedSessionFlagCount(JdbcClient jdbc) {
    return jdbc.sql("select count(*) from orphaned_session_flag").query(Long.class).single();
  }

  /**
   * Counts the statements the thread that calls {@link #count} prepares on the application's {@code
   * DataSource}: a datasource-level counter, so a loop of per-session queries shows up as growth.
   * Deliberately not annotated: it is passed to {@code SpringApplication} as a source, so no other
   * test's component scan finds it.
   */
  static class StatementCounter {

    private final AtomicLong statements = new AtomicLong();
    private volatile @Nullable Thread counted;

    @Bean
    static BeanPostProcessor statementCountingDataSource(ObjectProvider<StatementCounter> counter) {
      return new BeanPostProcessor() {
        @Override
        public @Nullable Object postProcessAfterInitialization(Object bean, String beanName) {
          if (bean instanceof DataSource dataSource && !(bean instanceof Proxy)) {
            return Proxy.newProxyInstance(
                DataSource.class.getClassLoader(),
                new Class<?>[] {DataSource.class},
                (proxy, method, args) -> {
                  Object result = invoke(dataSource, method, args);
                  return result instanceof Connection connection
                      ? countingConnection(connection, counter.getObject())
                      : result;
                });
          }
          return bean;
        }
      };
    }

    private static Connection countingConnection(Connection connection, StatementCounter counter) {
      return (Connection)
          Proxy.newProxyInstance(
              Connection.class.getClassLoader(),
              new Class<?>[] {Connection.class},
              (proxy, method, args) -> {
                if (method.getName().equals("prepareStatement")
                    || method.getName().equals("prepareCall")
                    || method.getName().equals("createStatement")) {
                  if (Thread.currentThread() == counter.counted) {
                    counter.statements.incrementAndGet();
                  }
                }
                return invoke(connection, method, args);
              });
    }

    private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
      try {
        return method.invoke(target, args);
      } catch (InvocationTargetException e) {
        throw e.getCause();
      }
    }

    long count(Runnable work) {
      statements.set(0);
      counted = Thread.currentThread();
      try {
        work.run();
      } finally {
        counted = null;
      }
      return statements.get();
    }
  }

  @Test
  void dutyBFlagsThenDeletesAnOrphanedSessionKeyFamilyAfterTwoRuns() {
    context = boot("0s");
    ReconciliationJob job = context.getBean(ReconciliationJob.class);
    StringRedisTemplate redis = context.getBean(StringRedisTemplate.class);
    UUID sessionId = UUID.randomUUID();
    redis.opsForValue().set("session:" + sessionId, "irrelevant");
    redis.opsForValue().set("routing:" + sessionId, "irrelevant");
    // No room references this session at all — orphaned by the "room no longer exists" branch.

    job.run();
    assertThat(redis.hasKey("session:" + sessionId)).isTrue(); // first observation only
    assertThat(orphanedSessionFlagRow(context, sessionId)).isPresent();

    job.run();
    assertThat(redis.hasKey("session:" + sessionId)).isFalse();
    assertThat(redis.hasKey("routing:" + sessionId)).isFalse();
    assertThat(orphanedSessionFlagRow(context, sessionId)).isEmpty();
  }

  @Test
  void dutyBNeverFlagsASessionStillOwnedByAnOpenRoom() {
    context = boot("0s");
    ReconciliationJob job = context.getBean(ReconciliationJob.class);
    StringRedisTemplate redis = context.getBean(StringRedisTemplate.class);
    UUID roomId = insertRoom(context, "IN_PROGRESS");
    UUID sessionId = UUID.randomUUID();
    setActiveSession(context, roomId, sessionId);
    redis.opsForValue().set("session:" + sessionId, "irrelevant");

    job.run();
    job.run();

    assertThat(redis.hasKey("session:" + sessionId)).isTrue();
    assertThat(orphanedSessionFlagRow(context, sessionId)).isEmpty();
  }

  @Test
  void dutyCFailsAnInProgressRoomWithNoSessionRecordBackToLobbyAfterTwoRuns() {
    context = boot("0s");
    ReconciliationJob job = context.getBean(ReconciliationJob.class);
    UUID roomId = insertRoom(context, "IN_PROGRESS");
    UUID sessionId = UUID.randomUUID();
    setActiveSession(context, roomId, sessionId);
    UUID userId = insertParticipant(context, roomId, "PLAYER", 1);
    setReady(context, roomId, userId, true);
    // Deliberately no "session:{sessionId}" Redis key — the Game Host record is gone.

    job.run();
    assertThat(roomRow(context, roomId).get("state")).isEqualTo("IN_PROGRESS"); // first observation
    assertThat(roomRow(context, roomId).get("reconcile_flagged_at")).isNotNull();

    job.run();
    Map<String, Object> row = roomRow(context, roomId);
    assertThat(row.get("state")).isEqualTo("LOBBY");
    assertThat(row.get("active_session_id")).isNull();
    assertThat(row.get("closed_at")).isNull(); // no closure, no history entry
    assertThat(row.get("reconcile_flagged_at")).isNull();
    assertThat(participantRow(context, roomId, userId).get("ready")).isEqualTo(false);
    assertThat(roomVersion(context, roomId)).isEqualTo(1L); // one GAME_ENDED broadcast
  }

  @Test
  void dutyCClearsTheFlagOnceTheSessionRecordReappears() {
    context = boot("0s");
    ReconciliationJob job = context.getBean(ReconciliationJob.class);
    StringRedisTemplate redis = context.getBean(StringRedisTemplate.class);
    UUID roomId = insertRoom(context, "IN_PROGRESS");
    UUID sessionId = UUID.randomUUID();
    setActiveSession(context, roomId, sessionId);

    job.run(); // first observation, flags the room
    assertThat(roomRow(context, roomId).get("reconcile_flagged_at")).isNotNull();

    redis.opsForValue().set("session:" + sessionId, "now present"); // Game Host recovers
    job.run();

    assertThat(roomRow(context, roomId).get("state")).isEqualTo("IN_PROGRESS");
    assertThat(roomRow(context, roomId).get("reconcile_flagged_at")).isNull();
  }

  @Test
  void dutyDClosesAnIdleLobbyRoomAfterTwoRuns() {
    context = boot("0s");
    ReconciliationJob job = context.getBean(ReconciliationJob.class);
    UUID roomId = insertRoom(context, "LOBBY");
    setLastActivityInThePast(context, roomId);

    job.run();
    assertThat(roomRow(context, roomId).get("state")).isEqualTo("LOBBY");

    job.run();
    Map<String, Object> row = roomRow(context, roomId);
    assertThat(row.get("state")).isEqualTo("CLOSED");
    assertThat(row.get("closed_reason")).isEqualTo("IDLE_TIMEOUT");
  }

  @Test
  void dutyEAdoptsARoomOwnedByADeadInstance() {
    context = boot("0s");
    ReconciliationJob job = context.getBean(ReconciliationJob.class);
    UUID roomId = insertRoom(context, "LOBBY");
    String deadInstanceId = "dead-instance:" + UUID.randomUUID();
    setOwner(context, roomId, deadInstanceId);

    job.run();

    Map<String, Object> row = roomRow(context, roomId);
    assertThat(row.get("owner_instance_id")).isNotEqualTo(deadInstanceId);
    assertThat(((Number) Objects.requireNonNull(row.get("owner_epoch"))).longValue())
        .isGreaterThan(0L);
  }

  @Test
  void startupSuppressionBlocksDutyCAndDRepairsButNotDetection() {
    context = boot("1h"); // never elapses within this test
    ReconciliationJob job = context.getBean(ReconciliationJob.class);
    UUID roomId = insertRoom(context, "LOBBY");
    setLastActivityInThePast(context, roomId);

    job.run();
    job.run();

    // Still detects (candidates would be non-zero on a real metrics scrape) but never repairs.
    assertThat(roomRow(context, roomId).get("state")).isEqualTo("LOBBY");
    assertThat(roomRow(context, roomId).get("reconcile_flagged_at")).isNull();
  }

  private static ConfigurableApplicationContext boot(String startupSuppression) {
    return boot(startupSuppression, new Class<?>[0]);
  }

  private static ConfigurableApplicationContext boot(
      String startupSuppression, Class<?>... extraSources) {
    List<Class<?>> sources = new ArrayList<>(List.of(TurnBaseApplication.class));
    sources.addAll(List.of(extraSources));
    return new SpringApplication(sources.toArray(Class<?>[]::new))
        .run(
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
            "--turnbase.room.idle-window=1s",
            "--turnbase.jobs.deadline-sweep.interval=1h",
            // Keeps the background sweep tick from racing this test's own manual job.run() calls.
            "--turnbase.jobs.reconcile.interval=1h",
            "--turnbase.jobs.reconcile.startup-suppression=" + startupSuppression,
            "--turnbase.jobs.reconcile.orphan-age=0s");
  }

  private static UUID insertGame(JdbcClient jdbcClient) {
    UUID gameId = UUID.randomUUID();
    jdbcClient
        .sql(
            "INSERT INTO game (game_id, title, short_description, badge, owner_user_id)"
                + " VALUES (:gameId, 'Test Game', 'A game for testing.', 'OFFICIAL', NULL)")
        .param("gameId", gameId)
        .update();
    return gameId;
  }

  private static UUID insertRoom(ConfigurableApplicationContext context, String state) {
    JdbcClient jdbcClient = context.getBean(JdbcClient.class);
    return insertRoomOfGame(jdbcClient, insertGame(jdbcClient), state);
  }

  private static UUID insertRoomOfGame(JdbcClient jdbcClient, UUID gameId, String state) {
    UUID roomId = UUID.randomUUID();
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

  private static UUID insertParticipant(
      ConfigurableApplicationContext context, UUID roomId, String role, long joinOrder) {
    UUID userId = UUID.randomUUID();
    String username = "user" + joinOrder + "-" + userId.toString().substring(0, 8);
    JdbcClient jdbcClient = context.getBean(JdbcClient.class);
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
            "insert into room_participant (room_id, user_id, role, join_order, connection_count,"
                + " ready) values (:roomId, :userId, :role, :joinOrder, 0, false)")
        .param("roomId", roomId)
        .param("userId", userId)
        .param("role", role)
        .param("joinOrder", joinOrder)
        .update();
    return userId;
  }

  private static void setReady(
      ConfigurableApplicationContext context, UUID roomId, UUID userId, boolean ready) {
    context
        .getBean(JdbcClient.class)
        .sql(
            "update room_participant set ready = :ready"
                + " where room_id = :roomId and user_id = :userId")
        .param("ready", ready)
        .param("roomId", roomId)
        .param("userId", userId)
        .update();
  }

  private static void setActiveSession(
      ConfigurableApplicationContext context, UUID roomId, UUID sessionId) {
    context
        .getBean(JdbcClient.class)
        .sql("update room set active_session_id = :sessionId where room_id = :roomId")
        .param("sessionId", sessionId)
        .param("roomId", roomId)
        .update();
  }

  /** Leaves {@code owner_epoch} at its default 0 so the claim script's own epoch (1) is greater. */
  private static void setOwner(
      ConfigurableApplicationContext context, UUID roomId, String instanceId) {
    context
        .getBean(JdbcClient.class)
        .sql("update room set owner_instance_id = :instanceId where room_id = :roomId")
        .param("instanceId", instanceId)
        .param("roomId", roomId)
        .update();
  }

  private static void setLastActivityInThePast(
      ConfigurableApplicationContext context, UUID roomId) {
    context
        .getBean(JdbcClient.class)
        .sql("update room set last_activity_at = now() - interval '1 hour' where room_id = :roomId")
        .param("roomId", roomId)
        .update();
  }

  private static void insertDueTeardown(
      ConfigurableApplicationContext context, UUID sessionId, UUID roomId) {
    context
        .getBean(JdbcClient.class)
        .sql(
            "insert into session_teardown (session_id, room_id, reason, next_attempt_at)"
                + " values (:sessionId, :roomId, 'ROOM_CLOSED', now() - interval '1 second')")
        .param("sessionId", sessionId)
        .param("roomId", roomId)
        .update();
  }

  private static Map<String, Object> teardownRow(
      ConfigurableApplicationContext context, UUID sessionId) {
    return context
        .getBean(JdbcClient.class)
        .sql("select * from session_teardown where session_id = :sessionId")
        .param("sessionId", sessionId)
        .query()
        .singleRow();
  }

  private static Optional<UUID> orphanedSessionFlagRow(
      ConfigurableApplicationContext context, UUID sessionId) {
    return context
        .getBean(JdbcClient.class)
        .sql("select session_id from orphaned_session_flag where session_id = :sessionId")
        .param("sessionId", sessionId)
        .query(UUID.class)
        .optional();
  }

  private static Map<String, Object> roomRow(ConfigurableApplicationContext context, UUID roomId) {
    return context
        .getBean(JdbcClient.class)
        .sql("SELECT * FROM room WHERE room_id = :roomId")
        .param("roomId", roomId)
        .query()
        .singleRow();
  }

  private static Map<String, Object> participantRow(
      ConfigurableApplicationContext context, UUID roomId, UUID userId) {
    return context
        .getBean(JdbcClient.class)
        .sql("select * from room_participant where room_id = :roomId and user_id = :userId")
        .param("roomId", roomId)
        .param("userId", userId)
        .query()
        .singleRow();
  }

  private static long roomVersion(ConfigurableApplicationContext context, UUID roomId) {
    Number version =
        context
            .getBean(JdbcClient.class)
            .sql("select version from room where room_id = :roomId")
            .param("roomId", roomId)
            .query(Number.class)
            .single();
    return version.longValue();
  }
}
