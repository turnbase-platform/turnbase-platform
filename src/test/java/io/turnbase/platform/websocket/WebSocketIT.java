package io.turnbase.platform.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.turnbase.platform.PostgresIntegrationTest;
import io.turnbase.platform.TurnBaseApplication;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
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

/**
 * {@code IMP-M2-02}'s own Done-when, end to end: {@code CONNECT} authenticates once from the
 * session cookie and an unauthenticated attempt never gets a session (clause 2); an unauthorized
 * {@code SUBSCRIBE} is silently dropped, not errored (clause 3, {@code websocket.md} §
 * Authorization).
 */
class WebSocketIT extends PostgresIntegrationTest {

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
  void connectWithNoSessionCookieNeverEstablishesASession() {
    WebSocketStompClient stompClient = newStompClient();
    CompletableFuture<StompSession> future =
        stompClient.connectAsync(
            wsUrl, new WebSocketHttpHeaders(), new StompSessionHandlerAdapter() {});

    assertThatThrownBy(() -> future.get(5, TimeUnit.SECONDS))
        .isInstanceOf(ExecutionException.class);
  }

  @Test
  void connectWithAValidSessionCookieEstablishesASession() throws Exception {
    String cookie = mintGuestCookie();
    WebSocketStompClient stompClient = newStompClient();
    WebSocketHttpHeaders handshakeHeaders = new WebSocketHttpHeaders();
    handshakeHeaders.add(HttpHeaders.COOKIE, cookie);

    StompSession session =
        stompClient
            .connectAsync(wsUrl, handshakeHeaders, new StompSessionHandlerAdapter() {})
            .get(5, TimeUnit.SECONDS);

    assertThat(session.isConnected()).isTrue();
    session.disconnect();
  }

  @Test
  void subscribingToARoomYouAreNotAMemberOfIsSilentlyDropped() throws Exception {
    String hostCookie = mintGuestCookie();
    UUID gameId = seedTwoPlayerGame();
    UUID roomId = createPublicRoom(hostCookie, gameId);
    String outsiderCookie = mintGuestCookie();

    WebSocketStompClient stompClient = newStompClient();
    WebSocketHttpHeaders handshakeHeaders = new WebSocketHttpHeaders();
    handshakeHeaders.add(HttpHeaders.COOKIE, outsiderCookie);
    List<Object> errorFrames = new CopyOnWriteArrayList<>();
    // Subscriptions run strictly sequentially on the main thread — the underlying client
    // WebSocket session does not tolerate concurrent sends from afterConnected() and this thread.
    StompSession session =
        stompClient
            .connectAsync(wsUrl, handshakeHeaders, new StompSessionHandlerAdapter() {})
            .get(5, TimeUnit.SECONDS);
    session.subscribe(
        "/user/queue/errors",
        new StompFrameHandler() {
          @Override
          public Type getPayloadType(StompHeaders headers) {
            return Map.class;
          }

          @Override
          public void handleFrame(StompHeaders headers, @Nullable Object payload) {
            errorFrames.add(payload);
          }
        });
    session.subscribe("/topic/room." + roomId, new StompSessionHandlerAdapter() {});
    Thread.sleep(1000); // no positive signal exists for "nothing happened" — a bounded wait is it.

    assertThat(errorFrames).isEmpty();
    session.disconnect();
  }

  private WebSocketStompClient newStompClient() {
    // No SockJS — the server offers a plain WebSocket endpoint only (websocket.md § Connection).
    WebSocketStompClient stompClient = new WebSocketStompClient(new StandardWebSocketClient());
    stompClient.setMessageConverter(new MappingJackson2MessageConverter());
    return stompClient;
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
