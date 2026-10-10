package io.turnbase.platform.chat;

import static org.assertj.core.api.Assertions.assertThat;

import io.turnbase.platform.PostgresIntegrationTest;
import io.turnbase.platform.TurnBaseApplication;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
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
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code room-chat-websocket.md} end to end on real STOMP clients: room-channel broadcast excludes
 * spectators and never advances {@code room.version} (IR-08); validation and business errors on
 * {@code /user/queue/errors}; team-channel delivery reaches only same-team members; the {@code
 * input-validation.md} 4 KB frame cap is enforced at the interceptor, ahead of any business
 * validation.
 */
class ChatControllerIT extends PostgresIntegrationTest {

  private final JsonMapper objectMapper = JsonMapper.builder().build();
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
            "--turnbase.rate-limit.minting.guest.capacity=1000000");
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
  void roomChannelBroadcastsExcludesSpectatorsAndNeverAdvancesVersion() throws Exception {
    String hostCookie = mintGuestCookie();
    UUID gameId = seedGameWithTeams();
    UUID roomId = createPublicRoom(hostCookie, gameId);
    UUID hostId = meUserId(hostCookie);

    String joinerCookie = mintGuestCookie();
    join(joinerCookie, roomId, "PLAYER");
    String spectatorCookie = mintGuestCookie();
    join(spectatorCookie, roomId, "SPECTATOR");

    Session host = connect(hostCookie, roomId, null);
    Session joiner = connect(joinerCookie, roomId, null);
    Session spectator = connect(spectatorCookie, roomId, null);

    long versionBeforeChat = roomVersion(roomId);
    host.send("SEND_CHAT_MESSAGE", "c1", Map.of("channel", "ROOM", "text", "hello room"));

    Map<String, Object> hostSeen = poll(host.chat);
    assertThat(hostSeen.get("type")).isEqualTo("CHAT_MESSAGE");
    assertThat(hostSeen.get("version")).isNull();
    Map<?, ?> payload = (Map<?, ?>) Objects.requireNonNull(hostSeen.get("payload"));
    assertThat(payload.get("channel")).isEqualTo("ROOM");
    assertThat(payload.get("teamId")).isNull();
    assertThat(payload.get("authorId")).isEqualTo(hostId.toString());
    assertThat(payload.get("text")).isEqualTo("hello room");
    assertThat(payload.get("messageId")).isNotNull();
    assertThat(payload.get("serverTimestamp")).isNotNull();

    Map<String, Object> joinerSeen = poll(joiner.chat);
    assertThat(((Map<?, ?>) Objects.requireNonNull(joinerSeen.get("payload"))).get("text"))
        .isEqualTo("hello room");

    // Spectators are excluded from the chat channel entirely (FR-CHAN-004) — silent drop at
    // SUBSCRIBE means nothing ever arrives, not even an error.
    assertThat(pollOrNull(spectator.chat)).isNull();

    // Chat never advances room.version (IR-08).
    assertThat(roomVersion(roomId)).isEqualTo(versionBeforeChat);
  }

  @Test
  void validationAndBusinessErrorsUseTheDocumentedCodes() throws Exception {
    String hostCookie = mintGuestCookie();
    UUID gameId = seedGameWithTeams();
    UUID roomId = createPublicRoom(hostCookie, gameId);

    String spectatorCookie = mintGuestCookie();
    join(spectatorCookie, roomId, "SPECTATOR");
    String outsiderCookie = mintGuestCookie();

    Session host = connect(hostCookie, roomId, null);
    Session spectator = connect(spectatorCookie, roomId, null);
    Session outsider = connect(outsiderCookie, roomId, null);

    host.send("SEND_CHAT_MESSAGE", "e1", Map.of("channel", "ROOM", "text", ""));
    assertThat(errorCode(poll(host.errors))).isEqualTo("MESSAGE_EMPTY");

    host.send("SEND_CHAT_MESSAGE", "e2", Map.of("channel", "ROOM", "text", "a".repeat(501)));
    assertThat(errorCode(poll(host.errors))).isEqualTo("MESSAGE_TOO_LONG");

    host.send("SEND_CHAT_MESSAGE", "e3", Map.of("channel", "SIDEBAR", "text", "hi"));
    assertThat(errorCode(poll(host.errors))).isEqualTo("VALIDATION_FAILED");

    host.send("NOT_A_REAL_COMMAND", "e4", Map.of("channel", "ROOM", "text", "hi"));
    assertThat(errorCode(poll(host.errors))).isEqualTo("UNKNOWN_COMMAND");

    spectator.send("SEND_CHAT_MESSAGE", "e5", Map.of("channel", "ROOM", "text", "hi"));
    assertThat(errorCode(poll(spectator.errors))).isEqualTo("SPECTATOR_CANNOT_CHAT");

    outsider.send("SEND_CHAT_MESSAGE", "e6", Map.of("channel", "ROOM", "text", "hi"));
    assertThat(errorCode(poll(outsider.errors))).isEqualTo("NOT_MEMBER");
  }

  @Test
  void teamChannelReachesOnlySameTeamAndRequiresGameSupport() throws Exception {
    String hostCookie = mintGuestCookie();
    UUID gameId = seedGameWithTeams();
    UUID roomId = createPublicRoom(hostCookie, gameId);

    // Room creation puts the host on Red team 0. Blue (count.max 1, size.max 1) fills first with
    // the joiner; a third player then has nowhere to go but back onto Red alongside the host.
    String joinerCookie = mintGuestCookie();
    join(joinerCookie, roomId, "PLAYER");
    String allyCookie = mintGuestCookie();
    join(allyCookie, roomId, "PLAYER");

    UUID redTeamId = firstTeamId(roomId, "Red");
    Session host = connect(hostCookie, roomId, redTeamId);
    Session joiner = connect(joinerCookie, roomId, redTeamId);
    Session ally = connect(allyCookie, roomId, redTeamId);

    host.send("SEND_CHAT_MESSAGE", "t1", Map.of("channel", "TEAM", "text", "red only"));

    Map<String, Object> allySeen = poll(ally.team);
    Map<?, ?> allyPayload = (Map<?, ?>) Objects.requireNonNull(allySeen.get("payload"));
    assertThat(allyPayload.get("channel")).isEqualTo("TEAM");
    assertThat(allyPayload.get("teamId")).isEqualTo(redTeamId.toString());
    assertThat(allyPayload.get("text")).isEqualTo("red only");

    // The off-team player is not subscribed to Red's team topic at all — silence.
    assertThat(pollOrNull(joiner.team)).isNull();

    // A game with no team templates at all has no team chat, regardless of membership.
    String soloHostCookie = mintGuestCookie();
    UUID noTeamGameId = seedGameWithoutTeams();
    UUID noTeamRoomId = createPublicRoom(soloHostCookie, noTeamGameId);
    Session soloHost = connect(soloHostCookie, noTeamRoomId, null);
    soloHost.send("SEND_CHAT_MESSAGE", "t2", Map.of("channel", "TEAM", "text", "no teams here"));
    assertThat(errorCode(poll(soloHost.errors))).isEqualTo("TEAM_CHAT_NOT_AVAILABLE");
  }

  @Test
  void oversizedFrameIsRejectedAtTheInterceptorBeforeBusinessValidation() throws Exception {
    String hostCookie = mintGuestCookie();
    UUID gameId = seedGameWithTeams();
    UUID roomId = createPublicRoom(hostCookie, gameId);
    Session host = connect(hostCookie, roomId, null);

    // input-validation.md: /app/room.*.chat is capped at 4 KB. Comfortably over that, and well
    // over the 500-code-point business limit too — the interceptor must reject this first.
    host.send("SEND_CHAT_MESSAGE", "o1", Map.of("channel", "ROOM", "text", "a".repeat(6000)));

    Map<String, Object> tooLarge = poll(host.errors);
    Map<?, ?> payload = (Map<?, ?>) Objects.requireNonNull(tooLarge.get("payload"));
    assertThat(payload.get("code")).isEqualTo("MESSAGE_TOO_LARGE");
    // Dropped at the interceptor, before the envelope was ever deserialized — no correlationId key.
    assertThat(payload.containsKey("correlationId")).isFalse();
  }

  // ---------------------------------------------------------------------
  // Harness
  // ---------------------------------------------------------------------

  private record Session(
      StompSession stompSession,
      UUID roomId,
      BlockingQueue<Map<String, Object>> chat,
      BlockingQueue<Map<String, Object>> team,
      BlockingQueue<Map<String, Object>> errors) {

    void send(String type, String correlationId, @Nullable Map<String, Object> payload) {
      Map<String, Object> envelope = new HashMap<>();
      envelope.put("type", type);
      envelope.put("correlationId", correlationId);
      if (payload != null) {
        envelope.put("payload", payload);
      }
      stompSession.send("/app/room." + roomId + ".chat", envelope);
    }
  }

  private Map<String, Object> poll(BlockingQueue<Map<String, Object>> queue) throws Exception {
    Map<String, Object> message = queue.poll(5, TimeUnit.SECONDS);
    return Objects.requireNonNull(message, "expected a message but none arrived");
  }

  private @Nullable Map<String, Object> pollOrNull(BlockingQueue<Map<String, Object>> queue)
      throws Exception {
    return queue.poll(500, TimeUnit.MILLISECONDS);
  }

  private String errorCode(Map<String, Object> errorFrame) {
    Map<?, ?> payload = (Map<?, ?>) Objects.requireNonNull(errorFrame.get("payload"));
    return (String) Objects.requireNonNull(payload.get("code"));
  }

  /** {@code teamId} non-null also subscribes {@code /topic/room.{roomId}.team.{teamId}}. */
  private Session connect(String cookie, UUID roomId, @Nullable UUID teamId) throws Exception {
    WebSocketStompClient stompClient = new WebSocketStompClient(new StandardWebSocketClient());
    stompClient.setMessageConverter(new MappingJackson2MessageConverter());
    WebSocketHttpHeaders handshakeHeaders = new WebSocketHttpHeaders();
    handshakeHeaders.add(HttpHeaders.COOKIE, cookie);

    StompSession session =
        stompClient
            .connectAsync(wsUrl, handshakeHeaders, new StompSessionHandlerAdapter() {})
            .get(5, TimeUnit.SECONDS);

    BlockingQueue<Map<String, Object>> chat = new LinkedBlockingQueue<>();
    BlockingQueue<Map<String, Object>> team = new LinkedBlockingQueue<>();
    BlockingQueue<Map<String, Object>> errors = new LinkedBlockingQueue<>();
    subscribe(session, "/topic/room." + roomId + ".chat", chat);
    if (teamId != null) {
      subscribe(session, "/topic/room." + roomId + ".team." + teamId, team);
    }
    subscribe(session, "/user/queue/errors", errors);
    Thread.sleep(300);
    return new Session(session, roomId, chat, team, errors);
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

  private void join(String cookie, UUID roomId, String role) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.add(HttpHeaders.COOKIE, cookie);
    HttpEntity<String> request = new HttpEntity<>("{\"role\":\"" + role + "\"}", headers);
    restTemplate.exchange(
        baseUrl + "/api/rooms/" + roomId + "/participants", HttpMethod.POST, request, String.class);
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

  private long roomVersion(UUID roomId) {
    Long version =
        context()
            .getBean(JdbcClient.class)
            .sql("select version from room where room_id = :roomId")
            .param("roomId", roomId)
            .query(Long.class)
            .single();
    return version == null ? -1 : version;
  }

  private UUID firstTeamId(UUID roomId, String templateName) {
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

  private UUID seedGameWithTeams() {
    return seedGame(
        """
        {"teamTemplates":[
          {"name":"Red","size":{"min":1,"max":2},"count":{"min":1,"max":2}},
          {"name":"Blue","size":{"min":1,"max":1},"count":{"min":1,"max":1}}
        ],
        "parameters":[]}
        """);
  }

  private UUID seedGameWithoutTeams() {
    return seedGame("{\"parameters\":[]}");
  }

  private UUID seedGame(String manifest) {
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
        .param("manifest", manifest)
        .update();
    jdbcClient
        .sql("update game set active_version_id = :versionId where game_id = :gameId")
        .param("versionId", versionId)
        .param("gameId", gameId)
        .update();
    return gameId;
  }

  private UUID createPublicRoom(String cookie, UUID gameId) {
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
    try {
      @SuppressWarnings("unchecked")
      Map<String, Object> body = objectMapper.readValue(response.getBody(), Map.class);
      return UUID.fromString((String) body.get("roomId"));
    } catch (Exception e) {
      throw new IllegalStateException("could not parse the create-room response", e);
    }
  }

  private String mintGuestCookie() {
    ResponseEntity<String> response =
        restTemplate.exchange(
            baseUrl + "/api/auth/guest", HttpMethod.POST, HttpEntity.EMPTY, String.class);
    java.util.List<String> setCookies =
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
