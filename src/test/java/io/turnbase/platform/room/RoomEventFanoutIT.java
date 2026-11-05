package io.turnbase.platform.room;

import static org.assertj.core.api.Assertions.assertThat;

import io.turnbase.platform.PostgresIntegrationTest;
import io.turnbase.platform.SharedApplication;
import io.turnbase.platform.UsesSharedApplication;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
 * {@code IMP-M2-04}'s own Done-when, end to end on a real STOMP client: the REST join transaction
 * of {@code room-rest-api.md} §5 broadcasts {@code PLAYER_JOINED} and the REST leave transaction of
 * §6 broadcasts {@code PLAYER_LEFT}, both version-stamped and both only after their own commit
 * ({@code IMP-GATE-43}/{@code IMP-GATE-44}) — proven by a subscriber that actually receives the
 * frames, not by asserting the database alone. Also proves the no-op paths (re-joining, leaving a
 * room never joined) broadcast nothing.
 *
 * <p>Requirements protected: {@code game-rooms.md} FR-JOIN-001, FR-LIFE-004.
 */
@UsesSharedApplication
class RoomEventFanoutIT extends PostgresIntegrationTest {

  private final JsonMapper objectMapper = JsonMapper.builder().build();
  private final RestTemplate restTemplate = new RestTemplate(new JdkClientHttpRequestFactory());

  private @Nullable ConfigurableApplicationContext context;
  private String baseUrl = "";
  private String wsUrl = "";

  @BeforeEach
  void bootApplication() {
    ConfigurableApplicationContext booted =
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
            "--turnbase.rate-limit.minting.guest.capacity=1000000");
    context = booted;
    int port = Integer.parseInt(booted.getEnvironment().getProperty("local.server.port"));
    baseUrl = "http://127.0.0.1:" + port;
    wsUrl = "ws://127.0.0.1:" + port + "/ws";
  }

  @Test
  void joinAndLeaveBroadcastVersionStampedEventsAfterTheirOwnCommit() throws Exception {
    String hostCookie = mintGuestCookie();
    UUID gameId = seedTwoPlayerGame();
    UUID roomId = createPublicRoom(hostCookie, gameId);

    BlockingQueue<Map<String, Object>> frames = subscribeToRoomTopic(hostCookie, roomId);

    String joinerCookie = mintGuestCookie();
    UUID joinerId = join(joinerCookie, roomId);

    Map<String, Object> playerJoined = frames.poll(5, TimeUnit.SECONDS);
    assertThat(playerJoined).isNotNull();
    assertThat(playerJoined.get("type")).isEqualTo("PLAYER_JOINED");
    assertThat(((Number) Objects.requireNonNull(playerJoined.get("version"))).longValue())
        .isEqualTo(1L);
    @SuppressWarnings("unchecked")
    Map<String, Object> joinedPayload =
        (Map<String, Object>) Objects.requireNonNull(playerJoined.get("payload"));
    assertThat(joinedPayload.get("playerId")).isEqualTo(joinerId.toString());
    assertThat(joinedPayload.get("role")).isEqualTo("PLAYER");

    assertThat(roomVersion(roomId)).isEqualTo(1L);

    // A re-join (already a member) is a no-op: no version bump, no broadcast.
    join(joinerCookie, roomId);
    assertThat(frames.poll(500, TimeUnit.MILLISECONDS)).isNull();
    assertThat(roomVersion(roomId)).isEqualTo(1L);

    leave(joinerCookie, roomId);

    Map<String, Object> playerLeft = frames.poll(5, TimeUnit.SECONDS);
    assertThat(playerLeft).isNotNull();
    assertThat(playerLeft.get("type")).isEqualTo("PLAYER_LEFT");
    assertThat(((Number) Objects.requireNonNull(playerLeft.get("version"))).longValue())
        .isEqualTo(2L);
    @SuppressWarnings("unchecked")
    Map<String, Object> leftPayload =
        (Map<String, Object>) Objects.requireNonNull(playerLeft.get("payload"));
    assertThat(leftPayload.get("playerId")).isEqualTo(joinerId.toString());
    assertThat(leftPayload.get("role")).isEqualTo("PLAYER");

    assertThat(roomVersion(roomId)).isEqualTo(2L);

    // Leaving again (already gone) is a no-op: no version bump, no broadcast.
    leave(joinerCookie, roomId);
    assertThat(frames.poll(500, TimeUnit.MILLISECONDS)).isNull();
    assertThat(roomVersion(roomId)).isEqualTo(2L);
  }

  private BlockingQueue<Map<String, Object>> subscribeToRoomTopic(String cookie, UUID roomId)
      throws Exception {
    WebSocketStompClient stompClient = new WebSocketStompClient(new StandardWebSocketClient());
    stompClient.setMessageConverter(new MappingJackson2MessageConverter());
    WebSocketHttpHeaders handshakeHeaders = new WebSocketHttpHeaders();
    handshakeHeaders.add(HttpHeaders.COOKIE, cookie);

    StompSession session =
        stompClient
            .connectAsync(wsUrl, handshakeHeaders, new StompSessionHandlerAdapter() {})
            .get(5, TimeUnit.SECONDS);

    BlockingQueue<Map<String, Object>> frames = new LinkedBlockingQueue<>();
    session.subscribe(
        "/topic/room." + roomId,
        new StompFrameHandler() {
          @Override
          public Type getPayloadType(StompHeaders headers) {
            return Map.class;
          }

          @Override
          public void handleFrame(StompHeaders headers, @Nullable Object payload) {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) Objects.requireNonNull(payload);
            frames.add(body);
          }
        });
    // Lets the SUBSCRIBE be processed before the mutating call below.
    Thread.sleep(300);
    return frames;
  }

  private UUID join(String cookie, UUID roomId) throws Exception {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.add(HttpHeaders.COOKIE, cookie);
    HttpEntity<String> request = new HttpEntity<>("{\"role\":\"PLAYER\"}", headers);
    restTemplate.exchange(
        baseUrl + "/api/rooms/" + roomId + "/participants", HttpMethod.POST, request, String.class);
    return meUserId(cookie);
  }

  private void leave(String cookie, UUID roomId) {
    HttpHeaders headers = new HttpHeaders();
    headers.add(HttpHeaders.COOKIE, cookie);
    restTemplate.exchange(
        baseUrl + "/api/rooms/" + roomId + "/participants/me",
        HttpMethod.DELETE,
        new HttpEntity<>(headers),
        Void.class);
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
    Number version =
        context()
            .getBean(JdbcClient.class)
            .sql("select version from room where room_id = :roomId")
            .param("roomId", roomId)
            .query(Number.class)
            .single();
    return version.longValue();
  }

  private UUID seedTwoPlayerGame() {
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
                1024, now(), 2, 2, false, true, true, now())
            """)
        .param("versionId", versionId)
        .param("gameId", gameId)
        .param(
            "manifest",
            """
            {"teamTemplates":[
              {"name":"A","size":{"min":1,"max":1},"count":{"min":1,"max":1}},
              {"name":"B","size":{"min":1,"max":1},"count":{"min":1,"max":1}}
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
            "{\"gameId\":\"%s\",\"visibility\":\"PUBLIC\"}".formatted(gameId), headers);
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
