package io.turnbase.platform.room;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import io.turnbase.grpc.gamehost.v1.CreateSessionRequest;
import io.turnbase.grpc.gamehost.v1.CreateSessionResponse;
import io.turnbase.grpc.gamehost.v1.GameHostRegistryServiceGrpc;
import io.turnbase.grpc.gamehost.v1.GameHostServiceGrpc;
import io.turnbase.grpc.gamehost.v1.ProcessResult;
import io.turnbase.grpc.gamehost.v1.ProcessStatus;
import io.turnbase.grpc.gamehost.v1.RegisterRequest;
import io.turnbase.platform.PostgresIntegrationTest;
import io.turnbase.platform.TurnBaseApplication;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
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

/**
 * {@code room-websocket.md}'s Client→Server Command Catalogue, end to end on real STOMP clients:
 * the Member Commands table ({@code TOGGLE_READY}, {@code SWITCH_TO_SPECTATOR}, {@code
 * SWITCH_TO_PLAYER}, {@code REQUEST_ROOM_STATE}), {@code IMP-M2-05}'s share of the Host Commands
 * table ({@code ADD_TEAM}, {@code REMOVE_TEAM}, {@code RESIZE_TEAM}, {@code REASSIGN_PLAYER},
 * {@code ADD_AI_PLAYER}), and {@code IMP-M2-06}'s share ({@code KICK_PLAYER}, {@code
 * CHANGE_PARAMETERS}, {@code START_GAME}, {@code TRANSFER_HOST}).
 */
class RoomCommandControllerIT extends PostgresIntegrationTest {

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final RestTemplate restTemplate = new RestTemplate(new JdkClientHttpRequestFactory());

  private @Nullable ConfigurableApplicationContext context;
  private String baseUrl = "";
  private String wsUrl = "";

  @BeforeEach
  void bootApplication() {
    ConfigurableApplicationContext booted =
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
            "--turnbase.rate-limit.minting.guest.capacity=1000000",
            "--turnbase.rate-limit.ws.command.capacity=1000");
    context = booted;
    int port = Integer.parseInt(booted.getEnvironment().getProperty("local.server.port"));
    baseUrl = "http://127.0.0.1:" + port;
    wsUrl = "ws://127.0.0.1:" + port + "/ws";
  }

  @AfterEach
  void stopApplication() {
    if (context != null) {
      context.close();
    }
  }

  @Test
  void readinessAndRoleSwitchCommandsEnforcePreconditionsAndBroadcast() throws Exception {
    String hostCookie = mintGuestCookie();
    UUID gameId = seedGame();
    UUID roomId = createPublicRoom(hostCookie, gameId);

    String joinerCookie = mintGuestCookie();
    UUID joinerId = join(joinerCookie, roomId);

    Session host = connect(hostCookie, roomId);
    Session joiner = connect(joinerCookie, roomId);

    // The host never sends TOGGLE_READY — ROLE_NOT_ELIGIBLE (IMP-GATE-45).
    host.send("TOGGLE_READY", "c1", Map.of("ready", true));
    Map<String, Object> hostError = poll(host.errors);
    assertThat(errorCode(hostError)).isEqualTo("ROLE_NOT_ELIGIBLE");
    assertThat(((Map<?, ?>) Objects.requireNonNull(hostError.get("payload"))).get("correlationId"))
        .isEqualTo("c1");

    joiner.send("TOGGLE_READY", "c2", Map.of("ready", true));
    Map<String, Object> readinessChanged = poll(host.roomTopic);
    assertThat(readinessChanged.get("type")).isEqualTo("READINESS_CHANGED");
    Map<?, ?> readinessPayload =
        (Map<?, ?>) Objects.requireNonNull(readinessChanged.get("payload"));
    assertThat(readinessPayload.get("playerId")).isEqualTo(joinerId.toString());
    assertThat(readinessPayload.get("ready")).isEqualTo(true);
    assertThat(readyFlag(roomId, joinerId)).isTrue();

    joiner.send("SWITCH_TO_SPECTATOR", "c3", null);
    Map<String, Object> roleChanged = poll(host.roomTopic);
    assertThat(roleChanged.get("type")).isEqualTo("PLAYER_ROLE_CHANGED");
    Map<?, ?> rolePayload = (Map<?, ?>) Objects.requireNonNull(roleChanged.get("payload"));
    assertThat(rolePayload.get("newRole")).isEqualTo("SPECTATOR");
    assertThat(rolePayload.get("teamId")).isNull();
    assertThat(rolePayload.get("readinessCleared")).isEqualTo(true);

    // The host cannot switch to spectator either — same ROLE_NOT_ELIGIBLE.
    host.send("SWITCH_TO_SPECTATOR", "c4", null);
    assertThat(errorCode(poll(host.errors))).isEqualTo("ROLE_NOT_ELIGIBLE");

    joiner.send("SWITCH_TO_PLAYER", "c5", null);
    Map<String, Object> backToPlayer = poll(host.roomTopic);
    assertThat(backToPlayer.get("type")).isEqualTo("PLAYER_ROLE_CHANGED");
    assertThat(((Map<?, ?>) Objects.requireNonNull(backToPlayer.get("payload"))).get("newRole"))
        .isEqualTo("PLAYER");
  }

  @Test
  void teamManagementCommandsEnforcePreconditionsAndBroadcast() throws Exception {
    String hostCookie = mintGuestCookie();
    UUID gameId = seedGame();
    UUID roomId = createPublicRoom(hostCookie, gameId);

    String joinerCookie = mintGuestCookie();
    UUID joinerId = join(joinerCookie, roomId);

    Session host = connect(hostCookie, roomId);
    Session joiner = connect(joinerCookie, roomId);

    // Non-host sender — NOT_HOST.
    joiner.send("ADD_TEAM", "n1", Map.of("templateName", "Red", "configuredSize", 2));
    assertThat(errorCode(poll(joiner.errors))).isEqualTo("NOT_HOST");

    host.send("ADD_TEAM", "t1", Map.of("templateName", "Red", "configuredSize", 2));
    Map<String, Object> teamAdded = poll(host.roomTopic);
    assertThat(teamAdded.get("type")).isEqualTo("TEAM_ADDED");
    Map<?, ?> teamAddedPayload = (Map<?, ?>) Objects.requireNonNull(teamAdded.get("payload"));
    assertThat(teamAddedPayload.get("templateName")).isEqualTo("Red");
    assertThat(teamAddedPayload.get("index")).isEqualTo(1);
    UUID newTeamId = UUID.fromString((String) teamAddedPayload.get("teamId"));

    // Red's count.max is 2 — a second addition is TEMPLATE_MAX_TEAMS.
    host.send("ADD_TEAM", "t2", Map.of("templateName", "Red", "configuredSize", 2));
    assertThat(errorCode(poll(host.errors))).isEqualTo("TEMPLATE_MAX_TEAMS");

    // Red's size.max is 2 — an oversized team is SIZE_OUT_OF_RANGE.
    host.send("REMOVE_TEAM", "t3", Map.of("teamId", newTeamId.toString()));
    Map<String, Object> teamRemoved = poll(host.roomTopic);
    assertThat(teamRemoved.get("type")).isEqualTo("TEAM_REMOVED");
    assertThat(((Map<?, ?>) Objects.requireNonNull(teamRemoved.get("payload"))).get("teamId"))
        .isEqualTo(newTeamId.toString());

    host.send("ADD_TEAM", "t4", Map.of("templateName", "Red", "configuredSize", 5));
    assertThat(errorCode(poll(host.errors))).isEqualTo("SIZE_OUT_OF_RANGE");

    // The host occupies Red's only team alone — resizing down to 0 is out of range; to 1 succeeds.
    UUID redTeamId = firstTeamId(roomId, "Red");
    host.send("RESIZE_TEAM", "t5", Map.of("teamId", redTeamId.toString(), "newSize", 0));
    assertThat(errorCode(poll(host.errors))).isEqualTo("SIZE_OUT_OF_RANGE");

    host.send("RESIZE_TEAM", "t6", Map.of("teamId", redTeamId.toString(), "newSize", 1));
    Map<String, Object> teamResized = poll(host.roomTopic);
    assertThat(teamResized.get("type")).isEqualTo("TEAM_RESIZED");
    assertThat(
            ((Map<?, ?>) Objects.requireNonNull(teamResized.get("payload")))
                .get("newConfiguredSize"))
        .isEqualTo(1);

    // The joiner auto-assigned to Blue (fewer members than Red at join time); Red is now full
    // again at size 1 with only the host — reassigning the joiner there is TEAM_FULL.
    host.send(
        "REASSIGN_PLAYER",
        "t7",
        Map.of("playerId", joinerId.toString(), "toTeamId", redTeamId.toString()));
    assertThat(errorCode(poll(host.errors))).isEqualTo("TEAM_FULL");

    // ADD_AI_PLAYER is wire-present, behaviour-absent (IR-12).
    UUID blueTeamId = firstTeamId(roomId, "Blue");
    host.send("ADD_AI_PLAYER", "t8", Map.of("teamId", blueTeamId.toString()));
    assertThat(errorCode(poll(host.errors))).isEqualTo("AI_NOT_SUPPORTED");

    host.send("NOT_A_REAL_COMMAND", "t9", null);
    assertThat(errorCode(poll(host.errors))).isEqualTo("UNKNOWN_COMMAND");
  }

  @Test
  void startGameCreatesTheSessionPersistsItAndServesTheBootstrap() throws Exception {
    List<CreateSessionRequest> received = new CopyOnWriteArrayList<>();
    Server fakeHost =
        NettyServerBuilder.forPort(0)
            .addService(
                new GameHostServiceGrpc.GameHostServiceImplBase() {
                  @Override
                  public void createSession(
                      CreateSessionRequest request, StreamObserver<CreateSessionResponse> out) {
                    received.add(request);
                    out.onNext(
                        CreateSessionResponse.newBuilder()
                            .setResult(
                                ProcessResult.newBuilder()
                                    .setSessionId(request.getSessionId())
                                    .setStatus(ProcessStatus.PROCESS_STATUS_OK))
                            .build());
                    out.onCompleted();
                  }
                })
            .build()
            .start();
    ManagedChannel registry =
        NettyChannelBuilder.forAddress("127.0.0.1", Integer.parseInt(baseUrl.split(":")[2]))
            .usePlaintext()
            .build();
    try {
      GameHostRegistryServiceGrpc.newBlockingStub(registry)
          .register(
              RegisterRequest.newBuilder()
                  .setInstanceId("fake-host")
                  .setAddress("127.0.0.1:" + fakeHost.getPort())
                  .addRuntimeTypes("jvm")
                  .build());

      String hostCookie = mintGuestCookie();
      UUID gameId = seedGame();
      UUID roomId = createPublicRoom(hostCookie, gameId);
      String aliceCookie = mintGuestCookie();
      UUID aliceId = join(aliceCookie, roomId);
      Session host = connect(hostCookie, roomId);
      Session alice = connect(aliceCookie, roomId);
      alice.send("TOGGLE_READY", "r1", Map.of("ready", true));
      poll(host.roomTopic);

      host.send("START_GAME", "s1", null);
      Map<String, Object> started = poll(host.roomTopic);
      assertThat(started.get("type")).isEqualTo("GAME_STARTED");
      Map<?, ?> payload = (Map<?, ?>) Objects.requireNonNull(started.get("payload"));
      String sessionId = (String) Objects.requireNonNull(payload.get("sessionId"));
      assertThat((String) payload.get("gameFrontendUrl"))
          .isEqualTo("http://" + gameId + ".games.localhost:8080/play/" + sessionId);

      JdbcClient jdbc = context().getBean(JdbcClient.class);
      assertThat(
              jdbc.sql("select state from room where room_id = :r")
                  .param("r", roomId)
                  .query(String.class)
                  .single())
          .isEqualTo("IN_PROGRESS");
      assertThat(
              jdbc.sql("select active_session_id from room where room_id = :r")
                  .param("r", roomId)
                  .query(UUID.class)
                  .single())
          .isEqualTo(UUID.fromString(sessionId));

      assertThat(received).hasSize(1);
      CreateSessionRequest request = received.get(0);
      assertThat(request.getSessionId()).isEqualTo(sessionId);
      assertThat(request.getContext().getGameId()).isEqualTo(gameId.toString());
      assertThat(request.getContext().getGameVersion()).isEqualTo("1.0.0");
      assertThat(request.getEntryPointFqcn()).isEqualTo("com.acme.Game");
      assertThat(request.getParameters().toStringUtf8()).contains("\"moveTimer\":60");
      assertThat(request.getTeamsList().stream().map(t -> t.getName()).toList())
          .contains("Red 1", "Blue 1");
      assertThat(
              request.getTeamsList().stream()
                  .flatMap(t -> t.getPlayersList().stream())
                  .map(p -> p.getPlayerId())
                  .toList())
          .contains(aliceId.toString());

      StringRedisTemplate redis = context().getBean(StringRedisTemplate.class);
      assertThat(redis.opsForValue().get("routing:" + sessionId)).isEqualTo("fake-host");
      assertThat(redis.hasKey("seq:" + sessionId)).isTrue();
      assertThat(redis.hasKey("delivered:" + sessionId)).isTrue();
      assertThat(redis.hasKey("session:" + sessionId)).isFalse();

      // /play/{sessionId}: the pinned bundle on the matching game origin, 404 anywhere else.
      String gameHost = gameId + ".games.localhost:8080";
      String bootstrap = rawGet("/play/" + sessionId, gameHost);
      assertThat(bootstrap).startsWith("HTTP/1.1 200");
      assertThat(bootstrap)
          .contains("<base href=\"/g/" + gameId + "/1.0.0/\">")
          .contains("sessionId: \"" + sessionId + "\"")
          .contains("src=\"main.js\"")
          .contains("no-store");
      assertThat(rawGet("/play/" + sessionId, UUID.randomUUID() + ".games.localhost:8080"))
          .startsWith("HTTP/1.1 404");
      assertThat(rawGet("/play/" + UUID.randomUUID(), gameHost)).startsWith("HTTP/1.1 404");
      assertThat(rawGet("/play/" + sessionId, "turnbase.localhost:8080"))
          .startsWith("HTTP/1.1 404");
    } finally {
      registry.shutdownNow();
      fakeHost.shutdownNow();
    }
  }

  private String rawGet(String path, String hostHeader) throws java.io.IOException {
    int port = Integer.parseInt(baseUrl.split(":")[2]);
    try (java.net.Socket socket = new java.net.Socket("127.0.0.1", port)) {
      socket
          .getOutputStream()
          .write(
              ("GET " + path + " HTTP/1.1\r\nHost: " + hostHeader + "\r\nConnection: close\r\n\r\n")
                  .getBytes(java.nio.charset.StandardCharsets.UTF_8));
      return new String(
          socket.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    }
  }

  @Test
  void requestRoomStateDeliversAUnicastVersionedSnapshot() throws Exception {
    String hostCookie = mintGuestCookie();
    UUID gameId = seedGame();
    UUID roomId = createPublicRoom(hostCookie, gameId);
    UUID hostId = meUserId(hostCookie);

    Session host = connect(hostCookie, roomId);
    host.send("REQUEST_ROOM_STATE", "s1", null);

    Map<String, Object> snapshot = poll(host.roomState);
    assertThat(snapshot.get("type")).isEqualTo("ROOM_STATE");
    assertThat(((Number) Objects.requireNonNull(snapshot.get("version"))).longValue())
        .isEqualTo(0L);
    Map<?, ?> payload = (Map<?, ?>) Objects.requireNonNull(snapshot.get("payload"));
    assertThat(payload.get("roomId")).isEqualTo(roomId.toString());
    assertThat(payload.get("state")).isEqualTo("LOBBY");
    assertThat(payload.get("hostId")).isEqualTo(hostId.toString());
    assertThat((List<?>) payload.get("aiPlayers")).isEmpty();
    assertThat((List<?>) payload.get("disconnectedPlayers")).isEmpty();
    List<?> players = (List<?>) Objects.requireNonNull(payload.get("players"));
    assertThat(players).hasSize(1);
    Map<?, ?> hostPlayer = (Map<?, ?>) players.get(0);
    assertThat(hostPlayer.get("playerId")).isEqualTo(hostId.toString());
    assertThat(hostPlayer.get("connected")).isEqualTo(true);
  }

  @Test
  void hostManagementCommandsEnforcePreconditionsAndBroadcast() throws Exception {
    String hostCookie = mintGuestCookie();
    UUID gameId = seedGame();
    UUID roomId = createPublicRoom(hostCookie, gameId);
    UUID hostId = meUserId(hostCookie);

    String aliceCookie = mintGuestCookie();
    UUID aliceId = join(aliceCookie, roomId);
    String bobCookie = mintGuestCookie();
    UUID bobId = join(bobCookie, roomId);

    Session host = connect(hostCookie, roomId);
    Session alice = connect(aliceCookie, roomId);
    Session bob = connect(bobCookie, roomId);

    // KICK_PLAYER: non-host sender, unknown target, then a real kick with the unicast KICKED
    // notification (room-websocket.md — no correlationId at all, not even null).
    alice.send("KICK_PLAYER", "k1", Map.of("playerId", bobId.toString()));
    assertThat(errorCode(poll(alice.errors))).isEqualTo("NOT_HOST");

    host.send("KICK_PLAYER", "k2", Map.of("playerId", UUID.randomUUID().toString()));
    assertThat(errorCode(poll(host.errors))).isEqualTo("PLAYER_NOT_FOUND");

    host.send("KICK_PLAYER", "k3", Map.of("playerId", bobId.toString()));
    Map<String, Object> kicked = poll(host.roomTopic);
    assertThat(kicked.get("type")).isEqualTo("PLAYER_KICKED");
    Map<?, ?> kickedPayload = (Map<?, ?>) Objects.requireNonNull(kicked.get("payload"));
    assertThat(kickedPayload.get("playerId")).isEqualTo(bobId.toString());
    assertThat(kickedPayload.get("byHostId")).isEqualTo(hostId.toString());

    Map<String, Object> kickedNotice = poll(bob.errors);
    assertThat(kickedNotice.get("type")).isEqualTo("ERROR");
    Map<?, ?> kickedNoticePayload = (Map<?, ?>) Objects.requireNonNull(kickedNotice.get("payload"));
    assertThat(kickedNoticePayload.get("code")).isEqualTo("KICKED");
    assertThat(kickedNoticePayload.containsKey("correlationId")).isFalse();

    // CHANGE_PARAMETERS: non-host, unknown key, type mismatch, out of range, then success —
    // the full merged set (not just the changed key) is what broadcasts.
    alice.send("CHANGE_PARAMETERS", "p1", Map.of("parameters", Map.of("moveTimer", 30)));
    assertThat(errorCode(poll(alice.errors))).isEqualTo("NOT_HOST");

    host.send("CHANGE_PARAMETERS", "p2", Map.of("parameters", Map.of("unknownKey", 1)));
    assertThat(errorCode(poll(host.errors))).isEqualTo("UNKNOWN_PARAMETER");

    host.send("CHANGE_PARAMETERS", "p3", Map.of("parameters", Map.of("moveTimer", "fast")));
    assertThat(errorCode(poll(host.errors))).isEqualTo("PARAMETER_TYPE_MISMATCH");

    host.send("CHANGE_PARAMETERS", "p4", Map.of("parameters", Map.of("moveTimer", 9999)));
    assertThat(errorCode(poll(host.errors))).isEqualTo("PARAMETER_OUT_OF_RANGE");

    host.send("CHANGE_PARAMETERS", "p5", Map.of("parameters", Map.of("moveTimer", 30)));
    Map<String, Object> paramsChanged = poll(host.roomTopic);
    assertThat(paramsChanged.get("type")).isEqualTo("PARAMETERS_CHANGED");
    Map<?, ?> paramsPayload = (Map<?, ?>) Objects.requireNonNull(paramsChanged.get("payload"));
    Map<?, ?> parameters = (Map<?, ?>) Objects.requireNonNull(paramsPayload.get("parameters"));
    assertThat(((Number) Objects.requireNonNull(parameters.get("moveTimer"))).intValue())
        .isEqualTo(30);

    // START_GAME: alice (a non-host PLAYER) is not ready — START_CONDITIONS_NOT_MET lists it.
    host.send("START_GAME", "s1", null);
    Map<String, Object> notMet = poll(host.errors);
    assertThat(errorCode(notMet)).isEqualTo("START_CONDITIONS_NOT_MET");
    Map<?, ?> notMetPayload = (Map<?, ?>) Objects.requireNonNull(notMet.get("payload"));
    Map<?, ?> details = (Map<?, ?>) Objects.requireNonNull(notMetPayload.get("details"));
    assertThat((List<?>) Objects.requireNonNull(details.get("failedConditions"))).isNotEmpty();

    alice.send("TOGGLE_READY", "r1", Map.of("ready", true));
    poll(host.roomTopic); // drain READINESS_CHANGED

    // Preconditions now hold, but no Game Host is registered (IMP-GATE-88): unicast failure, the
    // room stays in the Lobby and nothing is broadcast.
    host.send("START_GAME", "s2", null);
    Map<String, Object> startFailed = poll(host.errors);
    assertThat(errorCode(startFailed)).isEqualTo("GAME_START_FAILED");
    Map<?, ?> startFailedPayload = (Map<?, ?>) Objects.requireNonNull(startFailed.get("payload"));
    assertThat(
            ((Map<?, ?>) Objects.requireNonNull(startFailedPayload.get("details"))).get("reason"))
        .isEqualTo("NO_HOST_AVAILABLE");
    assertThat(pollOrNull(host.roomTopic)).isNull();

    host.send("REQUEST_ROOM_STATE", "s3", null);
    Map<String, Object> stillLobby = poll(host.roomState);
    assertThat(((Map<?, ?>) Objects.requireNonNull(stillLobby.get("payload"))).get("state"))
        .isEqualTo("LOBBY");

    // TRANSFER_HOST: non-host sender, a non-player target, then a real transfer.
    alice.send("TRANSFER_HOST", "x1", Map.of("newHostId", hostId.toString()));
    assertThat(errorCode(poll(alice.errors))).isEqualTo("NOT_HOST");

    host.send("TRANSFER_HOST", "x2", Map.of("newHostId", UUID.randomUUID().toString()));
    assertThat(errorCode(poll(host.errors))).isEqualTo("TARGET_NOT_PLAYER");

    host.send("TRANSFER_HOST", "x3", Map.of("newHostId", aliceId.toString()));
    Map<String, Object> transferred = poll(host.roomTopic);
    assertThat(transferred.get("type")).isEqualTo("HOST_TRANSFERRED");
    Map<?, ?> transferPayload = (Map<?, ?>) Objects.requireNonNull(transferred.get("payload"));
    assertThat(transferPayload.get("newHostId")).isEqualTo(aliceId.toString());
    assertThat(transferPayload.get("previousHostId")).isEqualTo(hostId.toString());
    assertThat(transferPayload.get("reason")).isEqualTo("VOLUNTARY");

    // The former host retains their player slot (FR-XFER-004) but no longer holds host commands.
    host.send("KICK_PLAYER", "x4", Map.of("playerId", aliceId.toString()));
    assertThat(errorCode(poll(host.errors))).isEqualTo("NOT_HOST");
  }

  // ---------------------------------------------------------------------
  // Harness
  // ---------------------------------------------------------------------

  private static final List<String> HOST_COMMANDS =
      List.of(
          "ADD_TEAM",
          "REMOVE_TEAM",
          "RESIZE_TEAM",
          "REASSIGN_PLAYER",
          "ADD_AI_PLAYER",
          "KICK_PLAYER",
          "CHANGE_PARAMETERS",
          "START_GAME",
          "TRANSFER_HOST");

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
    Map<String, Object> message = queue.poll(5, TimeUnit.SECONDS);
    return Objects.requireNonNull(message, "expected a message but none arrived");
  }

  /** Asserts silence: a short wait, then {@code null} if nothing arrived. */
  private @Nullable Map<String, Object> pollOrNull(BlockingQueue<Map<String, Object>> queue)
      throws Exception {
    return queue.poll(500, TimeUnit.MILLISECONDS);
  }

  /** {@code room-websocket.md} § Error Handling — {@code code} lives inside {@code payload}. */
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

  private UUID join(String cookie, UUID roomId) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.add(HttpHeaders.COOKIE, cookie);
    HttpEntity<String> request = new HttpEntity<>("{\"role\":\"PLAYER\"}", headers);
    restTemplate.exchange(
        baseUrl + "/api/rooms/" + roomId + "/participants", HttpMethod.POST, request, String.class);
    return meUserId(cookie);
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

  private boolean readyFlag(UUID roomId, UUID userId) {
    Boolean ready =
        context()
            .getBean(JdbcClient.class)
            .sql(
                "select ready from room_participant where room_id = :roomId and user_id ="
                    + " :userId")
            .param("roomId", roomId)
            .param("userId", userId)
            .query(Boolean.class)
            .single();
    return Boolean.TRUE.equals(ready);
  }

  private UUID firstTeamId(UUID roomId, String templateName) {
    // Red is template_index 0, Blue is template_index 1 (declaration order in seedGame's manifest).
    int templateIndex = "Red".equals(templateName) ? 0 : 1;
    return context()
        .getBean(JdbcClient.class)
        .sql(
            "select team_id from room_team where room_id = :roomId and template_index ="
                + " :templateIndex order by team_index limit 1")
        .param("roomId", roomId)
        .param("templateIndex", templateIndex)
        .query(UUID.class)
        .single();
  }

  private UUID seedGame() {
    JdbcClient jdbcClient = context().getBean(JdbcClient.class);
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

  private ConfigurableApplicationContext context() {
    return Objects.requireNonNull(context);
  }
}
