package io.turnbase.platform.room;

import static org.assertj.core.api.Assertions.assertThat;

import io.turnbase.platform.PostgresIntegrationTest;
import io.turnbase.platform.TurnBaseApplication;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
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
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code IMP-M2-02}'s room-specific half, end to end on a real STOMP client: a member's {@code
 * SUBSCRIBE} to {@code /topic/room.{roomId}} succeeds on the owning pod (this instance, since it is
 * the only one), and {@code room.RoomOwnershipLostEvent} — the mechanism {@code room-lifecycle.md}
 * §1.5 layer 3/§1.6 depends on — severs exactly that socket with a {@code
 * ROOM_OWNERSHIP_LOST}-coded STOMP {@code ERROR} frame, closing the connection. This is also the
 * only test that proves {@link
 * io.turnbase.platform.websocket.WebSocketConnectionRegistry#closeSession} actually closes a real
 * WebSocket, not merely that it sends a message.
 */
class RoomSubscriptionAuthorizerIT extends PostgresIntegrationTest {

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
  void roomOwnershipLostSeversExactlyThatRoomsSubscribedSockets() throws Exception {
    String hostCookie = mintGuestCookie();
    UUID gameId = seedTwoPlayerGame();
    UUID roomId = createPublicRoom(hostCookie, gameId);

    WebSocketStompClient stompClient = new WebSocketStompClient(new StandardWebSocketClient());
    stompClient.setMessageConverter(new MappingJackson2MessageConverter());
    WebSocketHttpHeaders handshakeHeaders = new WebSocketHttpHeaders();
    handshakeHeaders.add(HttpHeaders.COOKIE, hostCookie);

    CompletableFuture<byte[]> errorPayload = new CompletableFuture<>();
    CompletableFuture<Void> transportClosed = new CompletableFuture<>();
    StompSession session =
        stompClient
            .connectAsync(
                wsUrl,
                handshakeHeaders,
                new StompSessionHandlerAdapter() {
                  @Override
                  public void handleException(
                      StompSession s,
                      @Nullable StompCommand command,
                      StompHeaders headers,
                      byte[] payload,
                      Throwable exception) {
                    errorPayload.complete(payload);
                  }

                  @Override
                  public void handleTransportError(StompSession s, Throwable exception) {
                    transportClosed.complete(null);
                  }
                })
            .get(5, TimeUnit.SECONDS);
    session.subscribe("/topic/room." + roomId, new StompSessionHandlerAdapter() {});
    // Lets the SUBSCRIBE be processed (and this session tracked against roomId) before the event.
    Thread.sleep(300);

    context().publishEvent(new RoomOwnershipLostEvent(roomId, "test-induced eviction"));

    transportClosed.get(5, TimeUnit.SECONDS);
    @SuppressWarnings("unchecked")
    Map<String, Object> body =
        objectMapper.readValue(errorPayload.get(5, TimeUnit.SECONDS), Map.class);
    @SuppressWarnings("unchecked")
    Map<String, Object> errorEnvelopePayload =
        (Map<String, Object>) Objects.requireNonNull(body.get("payload"));
    assertThat(errorEnvelopePayload.get("code")).isEqualTo("ROOM_OWNERSHIP_LOST");
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
