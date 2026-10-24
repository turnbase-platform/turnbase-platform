package io.turnbase.platform.room;

import static org.assertj.core.api.Assertions.assertThat;

import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import io.turnbase.grpc.gamehost.v1.CreateSessionRequest;
import io.turnbase.grpc.gamehost.v1.CreateSessionResponse;
import io.turnbase.grpc.gamehost.v1.GameHostRegistryServiceGrpc;
import io.turnbase.grpc.gamehost.v1.GameHostServiceGrpc;
import io.turnbase.grpc.gamehost.v1.ProcessInputRequest;
import io.turnbase.grpc.gamehost.v1.ProcessInputResponse;
import io.turnbase.grpc.gamehost.v1.ProcessResult;
import io.turnbase.grpc.gamehost.v1.ProcessStatus;
import io.turnbase.grpc.gamehost.v1.RegisterRequest;
import io.turnbase.platform.PostgresIntegrationTest;
import io.turnbase.platform.TurnBaseApplication;
import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code IMP-M17-02} / {@code FR-DBQ-001}, {@code NFR-DBQ-004}: every statement the platform issues
 * on the game path costs the same whatever history the database retains.
 *
 * <p>An integration flow (room create and join, the listing, lobby and ready commands, game start,
 * a game action, one tick of the deadline sweep) runs against the live application, and every
 * statement it issues on {@code room}, {@code room_team} or {@code room_participant} is recorded at
 * the datasource with its bound parameters. Each is then {@code EXPLAIN (ANALYZE)}d twice with
 * those parameters: on a database that retains no history, and after 25,000 closed rooms (100,000
 * participants) have been seeded. Seeding is the only difference between the two runs.
 */
class GamePathQueryCostIT extends PostgresIntegrationTest {

  private static final int CLOSED_ROOMS = 25_000;
  private static final int LIVE_ROOMS = 300;
  private static final int HISTORY_PER_FLOW_USER = 40;
  private static final int TIMED_RUNS = 12;
  private static final Set<String> WATCHED = Set.of("room", "room_team", "room_participant");
  private static final Pattern TOUCHES_ROOM_TABLES =
      Pattern.compile("(?i)\\b(from|join|update|into)\\s+(room|room_team|room_participant)\\b");

  private final JsonMapper objectMapper = JsonMapper.builder().build();
  private final RestTemplate restTemplate = new RestTemplate(new JdkClientHttpRequestFactory());

  private @Nullable ConfigurableApplicationContext context;
  private String baseUrl = "";
  private String wsUrl = "";

  @AfterEach
  void stopApplication() {
    StatementRecorder.recording = false;
    if (context != null) {
      context.close();
    }
  }

  @Test
  void everyGamePathStatementCostsTheSameWhateverTheRetainedHistory() throws Exception {
    List<ProcessInputRequest> actions = new CopyOnWriteArrayList<>();
    Server fakeHost = fakeGameHost(actions);
    ManagedChannel registry = null;
    List<UUID> flowUsers = new ArrayList<>();
    List<StatementRecorder.Recorded> statements;
    try {
      boot();
      registry =
          NettyChannelBuilder.forAddress("127.0.0.1", Integer.parseInt(baseUrl.split(":")[2]))
              .usePlaintext()
              .build();
      GameHostRegistryServiceGrpc.newBlockingStub(registry)
          .register(
              RegisterRequest.newBuilder()
                  .setInstanceId("fake-host")
                  .setAddress("127.0.0.1:" + fakeHost.getPort())
                  .addRuntimeTypes("jvm")
                  .build());
      UUID gameId = seedGame();
      seedLiveRooms(gameId);
      statements = driveTheGamePath(gameId, actions, flowUsers);
    } finally {
      if (registry != null) {
        registry.shutdownNow();
      }
      fakeHost.shutdownNow();
    }
    assertThat(statements).as("statements recorded on the game path").hasSizeGreaterThan(25);

    // IMP-M18-03: the join_order and the joiner's username arrive with the statements that were
    // already running, and a create inserts its teams in one statement.
    assertThat(statements)
        .as("no separate max(join_order) or username lookup on the join path")
        .noneMatch(r -> r.key().startsWith("select max(join_order)"))
        .noneMatch(r -> r.key().startsWith("select username from app_user"));
    assertThat(statements)
        .as("one room_team insert text for every team of a create")
        .filteredOn(r -> r.key().startsWith("insert into room_team"))
        .hasSize(1);

    Map<String, Cost> empty = measureAll(statements);

    seedRetainedHistory(flowUsers);
    Map<String, Cost> retained = measureAll(statements);

    writeReport(statements, empty, retained);

    List<String> failures = new ArrayList<>();
    for (StatementRecorder.Recorded statement : statements) {
      Cost before = empty.get(statement.key());
      Cost after = retained.get(statement.key());
      if (before == null || after == null) {
        continue; // not explainable (an INSERT that conflicts with its own earlier run)
      }
      if (!after.seqScanned().isEmpty()) {
        failures.add("seq scan of " + after.seqScanned() + " in: " + statement.key());
      }
      if (after.rowsExamined() - before.rowsExamined() > 10) {
        failures.add(
            "examines %d rows on the fixture, %d on an empty history, in: %s"
                .formatted(after.rowsExamined(), before.rowsExamined(), statement.key()));
      }
    }
    assertThat(failures).as("FR-DBQ-001").isEmpty();
    assertThat(unexplainable(statements, empty))
        .as("statements that could not be explained must be inserts")
        .allSatisfy(sql -> assertThat(sql).startsWith("insert"));
  }

  // ---------------------------------------------------------------------
  // The flow
  // ---------------------------------------------------------------------

  private List<StatementRecorder.Recorded> driveTheGamePath(
      UUID gameId, List<ProcessInputRequest> actions, List<UUID> flowUsers) throws Exception {
    String hostCookie = mintGuestCookie();
    String aliceCookie = mintGuestCookie();
    String bobCookie = mintGuestCookie();
    String carolCookie = mintGuestCookie();
    UUID hostId = meUserId(hostCookie);
    UUID aliceId = meUserId(aliceCookie);
    flowUsers.addAll(List.of(hostId, aliceId, meUserId(bobCookie), meUserId(carolCookie)));

    StatementRecorder.STATEMENTS.clear();
    StatementRecorder.recording = true;

    UUID roomId = createPublicRoom(hostCookie, gameId);
    join(aliceCookie, roomId, "PLAYER");
    join(bobCookie, roomId, "SPECTATOR");
    get(bobCookie, "/api/rooms");
    get(bobCookie, "/api/rooms?search=test");
    get(bobCookie, "/api/rooms/" + roomId);

    Session host = connect(hostCookie, roomId);
    Session alice = connect(aliceCookie, roomId);

    alice.send("SWITCH_TO_SPECTATOR", "c1", null);
    pollType(host.roomTopic, "PLAYER_ROLE_CHANGED");
    alice.send("SWITCH_TO_PLAYER", "c2", null);
    pollType(host.roomTopic, "PLAYER_ROLE_CHANGED");
    alice.send("TOGGLE_READY", "c3", Map.of("ready", true));
    pollType(host.roomTopic, "READINESS_CHANGED");
    UUID redTeam = firstTeamId(roomId, 0);
    host.send("RESIZE_TEAM", "c4", Map.of("teamId", redTeam.toString(), "newSize", 1));
    pollType(host.roomTopic, "TEAM_RESIZED");
    host.send(
        "REASSIGN_PLAYER",
        "c5",
        Map.of("playerId", aliceId.toString(), "toTeamId", redTeam.toString()));
    assertThat(errorCode(poll(host.errors))).isEqualTo("TEAM_FULL");
    host.send("REQUEST_ROOM_STATE", "c6", null);
    poll(host.roomState);

    host.send("START_GAME", "c7", null);
    Map<String, Object> started = pollType(host.roomTopic, "GAME_STARTED");
    String sessionId =
        (String)
            Objects.requireNonNull(
                ((Map<?, ?>) Objects.requireNonNull(started.get("payload"))).get("sessionId"));

    java.util.HashMap<String, Object> action = new java.util.HashMap<>();
    action.put("type", "SUBMIT_ACTION");
    action.put("correlationId", "a1");
    action.put("payload", Map.of("move", 1));
    host.stompSession().send("/app/game." + sessionId + ".action", action);
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (actions.isEmpty() && System.nanoTime() < deadline) {
      Thread.sleep(50);
    }
    assertThat(actions).as("the game action reached the (fake) Game Host").isNotEmpty();

    ctx().getBean(GraceDeadlineSweepJob.class).run();

    // Bob's next join releases the seat he holds (room-rest-api.md §5, the release on join).
    UUID otherRoom = createPublicRoom(carolCookie, gameId);
    join(bobCookie, otherRoom, "PLAYER");
    assertThat(hostId).isNotEqualTo(aliceId);

    StatementRecorder.recording = false;
    host.stompSession().disconnect();
    alice.stompSession().disconnect();
    return StatementRecorder.snapshot();
  }

  // ---------------------------------------------------------------------
  // Fixtures
  // ---------------------------------------------------------------------

  /** The live rooms both runs share: a few hundred rooms with teams and live participants. */
  private void seedLiveRooms(UUID gameId) {
    JdbcClient jdbc = ctx().getBean(JdbcClient.class);
    jdbc.sql(
            """
            insert into app_user (user_id, kind, username, username_skeleton, guest_serial)
            select gen_random_uuid(), 'GUEST', 'live' || g, 'live' || g, 3000000000000 + g
              from generate_series(1, :users) g
            """)
        .param("users", LIVE_ROOMS * 2)
        .update();
    jdbc.sql(
            """
            insert into room (room_id, game_id, game_version, visibility, state,
                              spectating_enabled)
            select md5('live-room-' || i)::uuid, :gameId, '1.0.0', 'PUBLIC',
                   case when i % 3 = 0 then 'IN_PROGRESS' else 'LOBBY' end, true
              from generate_series(1, :rooms) i
            """)
        .param("gameId", gameId)
        .param("rooms", LIVE_ROOMS)
        .update();
    jdbc.sql(
            """
            insert into room_team (team_id, room_id, template_index, team_index, configured_size)
            select md5('live-team-' || i || '-' || t)::uuid, md5('live-room-' || i)::uuid,
                   t, 0, 2
              from generate_series(1, :rooms) i, generate_series(0, 1) t
            """)
        .param("rooms", LIVE_ROOMS)
        .update();
    jdbc.sql(
            """
            with u as (
              select user_id, row_number() over (order by guest_serial) as n
                from app_user where username like 'live%'
            )
            insert into room_participant (room_id, user_id, role, team_id, join_order,
                                          connection_count)
            select md5('live-room-' || i)::uuid, u.user_id, 'PLAYER',
                   md5('live-team-' || i || '-' || j)::uuid, j + 1, 1
              from generate_series(1, :rooms) i cross join generate_series(0, 1) j
              join u on u.n = (i - 1) * 2 + j + 1
            """)
        .param("rooms", LIVE_ROOMS)
        .update();
    analyze(jdbc);
  }

  /**
   * 25,000 closed rooms with four participants each. Closing a room does not set {@code left_at}
   * (data-model.md §2), so the last cycle of each user's seats stays "live"; the earlier ones were
   * released by that user's next join. Some closed rooms still carry this instance's id and an
   * elapsed migration window, a reservation due, or a departure nobody announced. Every user of the
   * flow gets {@code HISTORY_PER_FLOW_USER} departed seats and one stale one besides.
   */
  private void seedRetainedHistory(List<UUID> flowUsers) {
    JdbcClient jdbc = ctx().getBean(JdbcClient.class);
    String pod = ctx().getBean(PodIdentity.class).id();
    UUID gameId =
        jdbc.sql("select game_id from game where title = 'Test Game' limit 1")
            .query(UUID.class)
            .single();
    int users = 20_000;
    jdbc.sql(
            """
            insert into app_user (user_id, kind, username, username_skeleton, guest_serial)
            select gen_random_uuid(), 'GUEST', 'hist' || g, 'hist' || g, 4000000000000 + g
              from generate_series(1, :users) g
            """)
        .param("users", users)
        .update();
    jdbc.sql(
            """
            insert into room (room_id, game_id, game_version, visibility, state, closed_at,
                              closed_reason, owner_instance_id, migration_window_expires_at,
                              created_at, last_activity_at)
            select md5('closed-room-' || i)::uuid, :gameId, '1.0.0', 'PUBLIC', 'CLOSED',
                   now() - interval '1 day' - (i % 100) * interval '1 minute',
                   'NO_PLAYERS_REMAINING',
                   case when i % 5 = 0 then :pod end,
                   case when i % 5 = 0 then now() - interval '2 days' end,
                   now() - interval '2 days', now() - interval '1 day'
              from generate_series(1, :rooms) i
            """)
        .param("gameId", gameId)
        .param("pod", pod)
        .param("rooms", CLOSED_ROOMS)
        .update();
    jdbc.sql(
            """
            insert into room_team (team_id, room_id, template_index, team_index, configured_size)
            select md5('closed-team-' || i || '-' || t)::uuid, md5('closed-room-' || i)::uuid,
                   t, 0, 2
              from generate_series(1, :rooms) i, generate_series(0, 1) t
            """)
        .param("rooms", CLOSED_ROOMS)
        .update();
    jdbc.sql(
            """
            with u as (
              select user_id, row_number() over (order by guest_serial) as n
                from app_user where username like 'hist%'
            ), seat as (
              select i, j, ((i - 1) * 4 + j) % :users + 1 as n,
                     ((i - 1) * 4 + j) / :users as cycle
                from generate_series(1, :rooms) i, generate_series(0, 3) j
            )
            insert into room_participant (room_id, user_id, role, team_id, join_order,
                  joined_at, grace_reservation_id, grace_expires_at, left_at, leave_reason,
                  lost_announced_at)
            select md5('closed-room-' || s.i)::uuid, u.user_id, 'PLAYER',
                   md5('closed-team-' || s.i || '-' || (s.j % 2))::uuid, s.j + 1,
                   now() - interval '2 days',
                   case when s.cycle = 4 and s.i % 13 = 0 then gen_random_uuid() end,
                   case when s.cycle = 4 and s.i % 13 = 0 then now() - interval '1 day' end,
                   case when s.cycle < 4 then now() - interval '1 day' end,
                   case when s.cycle < 4
                        then case when s.i % 11 = 0 then 'LEFT' else 'ROOM_CLOSED' end end,
                   case when s.cycle < 4 and s.i % 7 = 0 then null
                        when s.cycle < 4 then now() - interval '1 day' end
              from seat s join u on u.n = s.n
            """)
        .param("users", users)
        .param("rooms", CLOSED_ROOMS)
        .update();
    // The flow's own users: departed seats in closed rooms, and one stale seat each. The stale
    // seat is the one a user's lookup may still read; the departed ones must not be read.
    for (UUID user : flowUsers) {
      jdbc.sql(
              """
              insert into room_participant (room_id, user_id, role, team_id, join_order,
                    left_at, leave_reason, lost_announced_at)
              select md5('closed-room-' || i)::uuid, :userId, 'PLAYER',
                     md5('closed-team-' || i || '-0')::uuid, 9,
                     case when i > :departed then null else now() - interval '1 day' end,
                     case when i > :departed then null else 'LEFT' end,
                     case when i > :departed then null else now() - interval '1 day' end
                from generate_series(1, :departed + 1) i
              """)
          .param("userId", user)
          .param("departed", HISTORY_PER_FLOW_USER)
          .update();
    }
    analyze(jdbc);
  }

  private static void analyze(JdbcClient jdbc) {
    jdbc.sql("analyze").update();
  }

  // ---------------------------------------------------------------------
  // EXPLAIN (ANALYZE)
  // ---------------------------------------------------------------------

  /** What one statement cost in one database: the room tables it scanned and read, and its time. */
  private record Cost(List<String> seqScanned, long rowsExamined, double millis) {}

  private Map<String, Cost> measureAll(List<StatementRecorder.Recorded> statements)
      throws Exception {
    Map<String, Cost> costs = new LinkedHashMap<>();
    try (Connection connection =
        DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
      connection.setAutoCommit(false);
      for (StatementRecorder.Recorded statement : statements) {
        Cost cost = measure(connection, statement);
        if (cost != null) {
          costs.put(statement.key(), cost);
        }
      }
    }
    return costs;
  }

  private @Nullable Cost measure(Connection connection, StatementRecorder.Recorded statement)
      throws Exception {
    List<String> seqScanned = new ArrayList<>();
    long rows = 0;
    double totalMillis = 0;
    try {
      for (int run = 0; run < TIMED_RUNS + 2; run++) {
        JsonNode explain = explain(connection, statement);
        if (run == 0) {
          rows = rowsExamined(explain.get(0).get("Plan"), seqScanned);
        }
        if (run >= 2) {
          totalMillis += explain.get(0).get("Execution Time").asDouble();
        }
      }
    } catch (SQLException e) {
      connection.rollback();
      return null;
    }
    return new Cost(seqScanned, rows, totalMillis / TIMED_RUNS);
  }

  /** Replays the statement under {@code EXPLAIN (ANALYZE)}, with its recorded parameters. */
  private JsonNode explain(Connection connection, StatementRecorder.Recorded statement)
      throws Exception {
    try (PreparedStatement ps =
        connection.prepareStatement("explain (analyze, format json) " + statement.sql())) {
      statement.replayParametersOn(ps);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return objectMapper.readTree(rs.getString(1));
      }
    } finally {
      connection.rollback();
    }
  }

  /**
   * The rows a plan reads from the watched tables: for every scan node, rows returned plus rows
   * removed by a filter or recheck, times loops. Collects any sequential scan of them into {@code
   * seqScanned}.
   */
  private static long rowsExamined(JsonNode plan, List<String> seqScanned) {
    long rows = 0;
    String nodeType = plan.path("Node Type").asString("");
    String relation = plan.path("Relation Name").asString("");
    if (nodeType.contains("Scan") && WATCHED.contains(relation)) {
      if (nodeType.contains("Seq Scan")) {
        seqScanned.add(relation);
      }
      long perLoop =
          plan.path("Actual Rows").asLong(0)
              + plan.path("Rows Removed by Filter").asLong(0)
              + plan.path("Rows Removed by Index Recheck").asLong(0);
      rows += perLoop * plan.path("Actual Loops").asLong(1);
    }
    for (JsonNode child : plan.path("Plans")) {
      rows += rowsExamined(child, seqScanned);
    }
    return rows;
  }

  private static List<String> unexplainable(
      List<StatementRecorder.Recorded> statements, Map<String, Cost> measured) {
    return statements.stream()
        .map(StatementRecorder.Recorded::key)
        .filter(key -> !measured.containsKey(key))
        .map(String::toLowerCase)
        .toList();
  }

  private void writeReport(
      List<StatementRecorder.Recorded> statements,
      Map<String, Cost> empty,
      Map<String, Cost> retained)
      throws IOException {
    StringBuilder report =
        new StringBuilder(
            "| # | Statement | Rows, empty | Rows, retained | ms, empty | ms, retained |\n"
                + "|--:|---|--:|--:|--:|--:|\n");
    int n = 0;
    for (StatementRecorder.Recorded statement : statements) {
      Cost before = empty.get(statement.key());
      Cost after = retained.get(statement.key());
      if (before == null || after == null) {
        continue;
      }
      String text = statement.key();
      report.append(
          "| %d | `%s` | %d | %d | %.3f | %.3f |\n"
              .formatted(
                  ++n,
                  text.length() > 90 ? text.substring(0, 90) + "…" : text,
                  before.rowsExamined(),
                  after.rowsExamined(),
                  before.millis(),
                  after.millis()));
    }
    Path target = Path.of("target", "game-path-query-cost.md");
    Files.createDirectories(target.getParent());
    Files.writeString(target, report.toString());
  }

  // ---------------------------------------------------------------------
  // The application
  // ---------------------------------------------------------------------

  private void boot() {
    StatementRecorder.recording = false;
    ConfigurableApplicationContext booted =
        SpringApplication.run(
            new Class<?>[] {TurnBaseApplication.class, StatementRecorder.class},
            new String[] {
              "--server.port=0",
              "--turnbase.orchestration.host-wait=0s",
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
              "--turnbase.rate-limit.minting.guest.capacity=1000000",
              "--turnbase.rate-limit.ws.command.capacity=1000",
              "--turnbase.room.lease.renew-interval=1h",
              // The background tick must not race this test's own sweep.run().
              "--turnbase.jobs.deadline-sweep.interval=1h"
            });
    context = booted;
    int port = Integer.parseInt(booted.getEnvironment().getProperty("local.server.port"));
    baseUrl = "http://127.0.0.1:" + port;
    wsUrl = "ws://127.0.0.1:" + port + "/ws";
  }

  private static Server fakeGameHost(List<ProcessInputRequest> actions) throws IOException {
    return NettyServerBuilder.forPort(0)
        .addService(
            new GameHostServiceGrpc.GameHostServiceImplBase() {
              @Override
              public void createSession(
                  CreateSessionRequest request, StreamObserver<CreateSessionResponse> out) {
                out.onNext(
                    CreateSessionResponse.newBuilder()
                        .setResult(
                            ProcessResult.newBuilder()
                                .setSessionId(request.getSessionId())
                                .setStatus(ProcessStatus.PROCESS_STATUS_OK))
                        .build());
                out.onCompleted();
              }

              @Override
              public void processInput(
                  ProcessInputRequest request, StreamObserver<ProcessInputResponse> out) {
                actions.add(request);
                out.onNext(
                    ProcessInputResponse.newBuilder()
                        .setResult(
                            ProcessResult.newBuilder().setStatus(ProcessStatus.PROCESS_STATUS_OK))
                        .build());
                out.onCompleted();
              }
            })
        .build()
        .start();
  }

  private UUID seedGame() {
    JdbcClient jdbcClient = ctx().getBean(JdbcClient.class);
    UUID gameId = UUID.randomUUID();
    UUID versionId = UUID.randomUUID();
    jdbcClient
        .sql(
            "insert into game (game_id, title, short_description, badge, listed)"
                + " values (:gameId, 'Test Game', 'A game for tests', 'OFFICIAL', true)")
        .param("gameId", gameId)
        .update();
    jdbcClient
        .sql(
            """
            insert into game_version (version_id, game_id, version, status, manifest,
                declared_size_bytes, upload_expires_at, min_players, max_players,
                supports_ai, supports_spectators, supports_mid_game_join, activated_at)
            values (:versionId, :gameId, '1.0.0', 'ACTIVE', :manifest::jsonb,
                1024, now(), 1, 5, false, true, true, now())
            """)
        .param("versionId", versionId)
        .param("gameId", gameId)
        .param(
            "manifest",
            """
            {"backend":{"entryPoint":"com.acme.Game"},"frontend":{"entryPoint":"main.js"},
            "teamTemplates":[
              {"name":"Red","size":{"min":1,"max":2},"count":{"min":1,"max":2}},
              {"name":"Blue","size":{"min":1,"max":1},"count":{"min":1,"max":1}}
            ],
            "parameters":[
              {"key":"moveTimer","displayName":"Move timer","type":"integer","min":0,"max":600,
               "default":60}
            ]}
            """)
        .update();
    jdbcClient
        .sql("update game set active_version_id = :versionId where game_id = :gameId")
        .param("versionId", versionId)
        .param("gameId", gameId)
        .update();
    return gameId;
  }

  private UUID firstTeamId(UUID roomId, int templateIndex) {
    return ctx()
        .getBean(JdbcClient.class)
        .sql(
            "select team_id from room_team where room_id = :roomId and template_index ="
                + " :templateIndex order by team_index limit 1")
        .param("roomId", roomId)
        .param("templateIndex", templateIndex)
        .query(UUID.class)
        .single();
  }

  private UUID createPublicRoom(String cookie, UUID gameId) throws Exception {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.add(HttpHeaders.COOKIE, cookie);
    HttpEntity<String> request =
        new HttpEntity<>(
            "{\"gameId\":\"%s\",\"visibility\":\"PUBLIC\",\"spectatingEnabled\":true}"
                .formatted(gameId),
            headers);
    ResponseEntity<String> response =
        restTemplate.exchange(baseUrl + "/api/rooms", HttpMethod.POST, request, String.class);
    @SuppressWarnings("unchecked")
    Map<String, Object> body = objectMapper.readValue(response.getBody(), Map.class);
    return UUID.fromString((String) body.get("roomId"));
  }

  private void join(String cookie, UUID roomId, String role) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.add(HttpHeaders.COOKIE, cookie);
    restTemplate.exchange(
        baseUrl + "/api/rooms/" + roomId + "/participants",
        HttpMethod.POST,
        new HttpEntity<>("{\"role\":\"" + role + "\"}", headers),
        String.class);
  }

  private void get(String cookie, String path) {
    HttpHeaders headers = new HttpHeaders();
    headers.add(HttpHeaders.COOKIE, cookie);
    restTemplate.exchange(baseUrl + path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
  }

  private UUID meUserId(String cookie) {
    HttpHeaders headers = new HttpHeaders();
    headers.add(HttpHeaders.COOKIE, cookie);
    ResponseEntity<String> response =
        restTemplate.exchange(
            baseUrl + "/api/auth/session", HttpMethod.GET, new HttpEntity<>(headers), String.class);
    try {
      @SuppressWarnings("unchecked")
      Map<String, Object> body = objectMapper.readValue(response.getBody(), Map.class);
      return UUID.fromString((String) Objects.requireNonNull(body.get("userId")));
    } catch (Exception e) {
      throw new IllegalStateException("could not resolve the caller's own userId", e);
    }
  }

  private String mintGuestCookie() {
    ResponseEntity<String> response =
        restTemplate.exchange(
            baseUrl + "/api/auth/guest", HttpMethod.POST, HttpEntity.EMPTY, String.class);
    List<String> setCookies =
        Objects.requireNonNull(response.getHeaders().get(HttpHeaders.SET_COOKIE));
    return setCookies.stream()
        .filter(c -> c.startsWith("turnbase_session="))
        .findFirst()
        .orElseThrow()
        .split(";", 2)[0];
  }

  private ConfigurableApplicationContext ctx() {
    return Objects.requireNonNull(context);
  }

  // ---------------------------------------------------------------------
  // STOMP
  // ---------------------------------------------------------------------

  private static final List<String> HOST_COMMANDS =
      List.of("RESIZE_TEAM", "REASSIGN_PLAYER", "START_GAME");

  private record Session(
      StompSession stompSession,
      UUID roomId,
      BlockingQueue<Map<String, Object>> roomTopic,
      BlockingQueue<Map<String, Object>> errors,
      BlockingQueue<Map<String, Object>> roomState) {

    void send(String type, String correlationId, @Nullable Map<String, Object> payload) {
      java.util.HashMap<String, Object> envelope = new java.util.HashMap<>();
      envelope.put("type", type);
      envelope.put("correlationId", correlationId);
      if (payload != null) {
        envelope.put("payload", payload);
      }
      String channel = HOST_COMMANDS.contains(type) ? "host" : "member";
      stompSession.send("/app/room." + roomId + "." + channel, envelope);
    }
  }

  private Map<String, Object> poll(BlockingQueue<Map<String, Object>> queue) throws Exception {
    Map<String, Object> message = queue.poll(10, TimeUnit.SECONDS);
    return Objects.requireNonNull(message, "expected a message but none arrived");
  }

  /** The next message of {@code type}; anything else that arrives first is skipped. */
  private Map<String, Object> pollType(BlockingQueue<Map<String, Object>> queue, String type)
      throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
    while (System.nanoTime() < deadline) {
      Map<String, Object> message = queue.poll(1, TimeUnit.SECONDS);
      if (message != null && type.equals(message.get("type"))) {
        return message;
      }
    }
    throw new AssertionError("no " + type + " arrived");
  }

  private String errorCode(Map<String, Object> errorFrame) {
    Map<?, ?> payload = (Map<?, ?>) Objects.requireNonNull(errorFrame.get("payload"));
    return (String) Objects.requireNonNull(payload.get("code"));
  }

  private Session connect(String cookie, UUID roomId) throws Exception {
    WebSocketStompClient stompClient = new WebSocketStompClient(new StandardWebSocketClient());
    stompClient.setMessageConverter(new MappingJackson2MessageConverter());
    WebSocketHttpHeaders handshakeHeaders = new WebSocketHttpHeaders();
    handshakeHeaders.add(HttpHeaders.COOKIE, cookie);
    StompSession session =
        stompClient
            .connectAsync(wsUrl, handshakeHeaders, new StompSessionHandlerAdapter() {})
            .get(5, TimeUnit.SECONDS);
    BlockingQueue<Map<String, Object>> roomTopic = new LinkedBlockingQueue<>();
    BlockingQueue<Map<String, Object>> errors = new LinkedBlockingQueue<>();
    BlockingQueue<Map<String, Object>> roomState = new LinkedBlockingQueue<>();
    subscribe(session, "/topic/room." + roomId, roomTopic);
    subscribe(session, "/user/queue/errors", errors);
    subscribe(session, "/user/queue/room-state", roomState);
    Thread.sleep(300);
    return new Session(session, roomId, roomTopic, errors, roomState);
  }

  private void subscribe(
      StompSession session, String destination, BlockingQueue<Map<String, Object>> sink) {
    session.subscribe(
        destination,
        new StompFrameHandler() {
          @Override
          public Type getPayloadType(StompHeaders headers) {
            return Map.class;
          }

          @Override
          public void handleFrame(StompHeaders headers, @Nullable Object payload) {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) Objects.requireNonNull(payload);
            sink.add(body);
          }
        });
  }

  // ---------------------------------------------------------------------
  // Recording every prepared statement, with its parameters
  // ---------------------------------------------------------------------

  /**
   * Records the SQL and the bound parameters of every statement the application prepares on the
   * room tables. Deliberately not annotated: it is passed to {@code SpringApplication} as a source,
   * so no other test's component scan finds it.
   */
  static class StatementRecorder {

    static final List<Recorded> STATEMENTS = new CopyOnWriteArrayList<>();
    static volatile boolean recording;

    /** One prepared statement: its SQL and the {@code setXxx(int, …)} calls that bound it. */
    record Recorded(String sql, String key, List<BoundCall> parameters) {

      void replayParametersOn(PreparedStatement ps) throws Exception {
        for (BoundCall call : parameters) {
          try {
            call.method().invoke(ps, call.args());
          } catch (InvocationTargetException e) {
            throw e.getCause() instanceof Exception cause ? cause : e;
          }
        }
      }
    }

    record BoundCall(Method method, @Nullable Object[] args) {}

    /** One entry per distinct statement text: the first execution's parameters. */
    static List<Recorded> snapshot() {
      Map<String, Recorded> distinct = new LinkedHashMap<>();
      for (Recorded statement : STATEMENTS) {
        distinct.putIfAbsent(statement.key(), statement);
      }
      return List.copyOf(distinct.values());
    }

    @Bean
    static BeanPostProcessor recordingDataSource() {
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
                    ? recordingConnection(connection)
                    : result;
              });
        }
      };
    }

    private static Connection recordingConnection(Connection connection) {
      InvocationHandler handler =
          (proxy, method, args) -> {
            Object result = invoke(connection, method, args);
            if (method.getName().equals("prepareStatement")
                && args != null
                && args[0] instanceof String sql
                && result instanceof PreparedStatement ps) {
              return recordingStatement(ps, sql);
            }
            return result;
          };
      return (Connection)
          Proxy.newProxyInstance(
              StatementRecorder.class.getClassLoader(), new Class<?>[] {Connection.class}, handler);
    }

    private static PreparedStatement recordingStatement(PreparedStatement ps, String sql) {
      String key = sql.replaceAll("\\s+", " ").trim();
      boolean relevant = TOUCHES_ROOM_TABLES.matcher(key).find();
      List<BoundCall> bound = new ArrayList<>();
      InvocationHandler handler =
          (proxy, method, args) -> {
            if (relevant
                && method.getName().startsWith("set")
                && args != null
                && args.length >= 2
                && args[0] instanceof Integer) {
              bound.add(new BoundCall(method, args.clone()));
            }
            if (relevant && method.getName().startsWith("execute") && recording) {
              STATEMENTS.add(new Recorded(sql, key, List.copyOf(bound)));
            }
            return invoke(ps, method, args);
          };
      return (PreparedStatement)
          Proxy.newProxyInstance(
              StatementRecorder.class.getClassLoader(),
              new Class<?>[] {PreparedStatement.class},
              handler);
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
