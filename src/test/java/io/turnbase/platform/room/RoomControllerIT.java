package io.turnbase.platform.room;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.turnbase.platform.PostgresIntegrationTest;
import io.turnbase.platform.TurnBaseApplication;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.client.RestTemplate;

/**
 * {@code IMP-M2-01}'s own Done-when, end to end: create returns {@code 201} with {@code Location}
 * and the full resource (clause 1); the listing predicate (clause 2); invite-code resolution is
 * never a {@code 404} (clause 3); join is one endpoint by role and leave is the {@code DELETE}
 * (clause 4); a redirected claim that never resolves is a {@code 503} (clause 5).
 */
class RoomControllerIT extends PostgresIntegrationTest {

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final RestTemplate restTemplate = new RestTemplate(new JdkClientHttpRequestFactory());

  private @Nullable ConfigurableApplicationContext context;
  private @Nullable ConfigurableApplicationContext secondContext;
  private JdbcClient jdbcClient;
  private String baseUrl;

  {
    restTemplate.setErrorHandler(new NoOpResponseErrorHandler());
  }

  @BeforeEach
  void bootApplication() {
    ConfigurableApplicationContext booted = boot();
    context = booted;
    int port = Integer.parseInt(booted.getEnvironment().getProperty("local.server.port"));
    baseUrl = "http://127.0.0.1:" + port;
    jdbcClient = booted.getBean(JdbcClient.class);
  }

  @AfterEach
  void stopApplication() {
    if (secondContext != null) {
      secondContext.close();
    }
    if (context != null) {
      context.close();
    }
  }

  private ConfigurableApplicationContext boot() {
    return SpringApplication.run(
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
        "--turnbase.room.spectator-capacity=2",
        "--spring.data.redis.url=" + redisUrl(),
        "--turnbase.rate-limit.auth.failures-before-lockout=1000000",
        "--turnbase.rate-limit.minting.guest.capacity=1000000",
        "--turnbase.rate-limit.minting.registration.capacity=1000000");
  }

  @Test
  void createReturnsTheFullResourceWithLocationAndInstanceHeader() throws Exception {
    Registered host = registerUser();
    UUID gameId = seedTwoPlayerGame(true, true);

    ResponseEntity<String> response =
        postWithCookie(
            "/api/rooms",
            host.cookie(),
            "{\"gameId\":\"%s\",\"visibility\":\"PUBLIC\",\"spectatingEnabled\":true}"
                .formatted(gameId));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(response.getHeaders().getFirst(HttpHeaders.LOCATION)).isNotNull();
    assertThat(response.getHeaders().getFirst("X-TurnBase-Room-Instance")).isNotBlank();
    Map<String, Object> body = parse(response);
    assertThat(body.get("hostUsername")).isEqualTo(host.username());
    assertThat(body.get("inviteCode")).isNotNull();
    assertThat(body.get("state")).isEqualTo("LOBBY");
    assertThat(numberOf(body, "playerCount")).isEqualTo(1);
    assertThat(numberOf(body, "playerCapacity")).isEqualTo(2);
  }

  @Test
  void inviteOnlyRoomIsAbsentFromTheListButResolvesByCode() throws Exception {
    Registered host = registerUser();
    Registered other = registerUser();
    UUID gameId = seedTwoPlayerGame(true, true);
    Map<String, Object> created =
        parse(
            postWithCookie(
                "/api/rooms",
                host.cookie(),
                "{\"gameId\":\"%s\",\"visibility\":\"INVITE_ONLY\"}".formatted(gameId)));
    String inviteCode = (String) created.get("inviteCode");

    Map<String, Object> listing = parse(getWithCookie("/api/rooms", other.cookie()));
    assertThat(roomsForGame(contentOf(listing), gameId)).isEmpty();

    Map<String, Object> resolved =
        parse(getWithCookie("/api/rooms?inviteCode=" + inviteCode, other.cookie()));
    List<Map<String, Object>> resolvedRows = contentOf(resolved);
    assertThat(resolvedRows).hasSize(1);
    assertThat(resolvedRows.get(0).get("roomId")).isEqualTo(created.get("roomId"));
  }

  @Test
  void aWrongInviteCodeResolvesToAnEmptyListNeverA404() throws Exception {
    Registered viewer = registerUser();

    ResponseEntity<String> response =
        getWithCookie("/api/rooms?inviteCode=doesnotexist", viewer.cookie());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(contentOf(parse(response))).isEmpty();
  }

  @Test
  void friendsOnlyRoomIsVisibleOnlyToAFriend() throws Exception {
    Registered host = registerUser();
    Registered friend = registerUser();
    Registered stranger = registerUser();
    befriend(host, friend);
    UUID gameId = seedTwoPlayerGame(true, true);
    postWithCookie(
        "/api/rooms",
        host.cookie(),
        "{\"gameId\":\"%s\",\"visibility\":\"FRIENDS_ONLY\"}".formatted(gameId));

    List<Map<String, Object>> friendView =
        roomsForGame(contentOf(parse(getWithCookie("/api/rooms", friend.cookie()))), gameId);
    List<Map<String, Object>> strangerView =
        roomsForGame(contentOf(parse(getWithCookie("/api/rooms", stranger.cookie()))), gameId);

    assertThat(friendView).hasSize(1);
    assertThat(strangerView).isEmpty();
  }

  @Test
  void joinInsertsAParticipantAndLeaveIsIdempotent() throws Exception {
    Registered host = registerUser();
    Registered joiner = registerUser();
    UUID gameId = seedTwoPlayerGame(true, true);
    UUID roomId = createPublicRoom(host, gameId);

    ResponseEntity<String> joinResponse =
        postWithCookie(
            "/api/rooms/" + roomId + "/participants", joiner.cookie(), "{\"role\":\"PLAYER\"}");
    assertThat(joinResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    Map<String, Object> joinBody = parse(joinResponse);
    Map<String, Object> room = mapOf(joinBody, "room");
    assertThat(numberOf(room, "playerCount")).isEqualTo(2);
    assertThat(joinBody.get("releasedRoom")).isNull();

    ResponseEntity<String> firstLeave =
        deleteWithCookie("/api/rooms/" + roomId + "/participants/me", joiner.cookie());
    ResponseEntity<String> secondLeave =
        deleteWithCookie("/api/rooms/" + roomId + "/participants/me", joiner.cookie());
    assertThat(firstLeave.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    assertThat(secondLeave.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

    Long remaining =
        jdbcClient
            .sql(
                "select count(*) from room_participant where room_id = :roomId and user_id ="
                    + " :userId and left_at is null")
            .param("roomId", roomId)
            .param("userId", joiner.userId())
            .query(Long.class)
            .single();
    assertThat(remaining).isZero();
  }

  @Test
  void joiningANewRoomReleasesTheCallersPreviousRoom() throws Exception {
    Registered host = registerUser();
    UUID gameId = seedTwoPlayerGame(true, true);
    UUID firstRoomId = createPublicRoom(host, gameId);
    UUID secondRoomId = createPublicRoom(host, gameId);

    ResponseEntity<String> secondJoin =
        postWithCookie(
            "/api/rooms/" + secondRoomId + "/participants", host.cookie(), "{\"role\":\"PLAYER\"}");

    // Creating the second room already released the host from the first (FR-JOIN-007), so the
    // no-op re-join to the second room's own creator reports no further release.
    assertThat(secondJoin.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    Long stillActiveInFirst =
        jdbcClient
            .sql(
                "select count(*) from room_participant where room_id = :roomId and user_id ="
                    + " :userId and left_at is null")
            .param("roomId", firstRoomId)
            .param("userId", host.userId())
            .query(Long.class)
            .single();
    assertThat(stillActiveInFirst).isZero();
  }

  @Test
  void aFullRoomRejectsAFurtherPlayerJoinWith422() throws Exception {
    Registered host = registerUser();
    Registered second = registerUser();
    Registered third = registerUser();
    UUID gameId = seedTwoPlayerGame(true, true);
    UUID roomId = createPublicRoom(host, gameId);
    postWithCookie(
        "/api/rooms/" + roomId + "/participants", second.cookie(), "{\"role\":\"PLAYER\"}");

    ResponseEntity<String> thirdJoin =
        postWithCookie(
            "/api/rooms/" + roomId + "/participants", third.cookie(), "{\"role\":\"PLAYER\"}");

    assertThat(thirdJoin.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
  }

  @Test
  void aSecondInstanceRedirectedForeverEventuallyReturns503() throws Exception {
    Registered host = registerUser();
    UUID gameId = seedTwoPlayerGame(true, true);
    UUID roomId = createPublicRoom(host, gameId);

    ConfigurableApplicationContext bootedSecond = boot();
    secondContext = bootedSecond;
    int secondPort =
        Integer.parseInt(bootedSecond.getEnvironment().getProperty("local.server.port"));
    String secondBaseUrl = "http://127.0.0.1:" + secondPort;
    Registered joiner = registerUser();

    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.add(HttpHeaders.COOKIE, joiner.cookie());
    ResponseEntity<String> response =
        restTemplate.exchange(
            secondBaseUrl + "/api/rooms/" + roomId + "/participants",
            HttpMethod.POST,
            new HttpEntity<>("{\"role\":\"PLAYER\"}", headers),
            String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isNotNull();
  }

  private void befriend(Registered a, Registered b) throws Exception {
    ResponseEntity<String> sendResponse =
        postWithCookie(
            "/api/friend-requests",
            a.cookie(),
            "{\"recipientUsername\":\"%s\"}".formatted(b.username()));
    Map<String, Object> friendRequest = mapOf(parse(sendResponse), "friendRequest");
    postWithCookie(
        "/api/friend-requests/" + friendRequest.get("requestId") + "/accept", b.cookie(), "{}");
  }

  private UUID createPublicRoom(Registered host, UUID gameId) throws Exception {
    ResponseEntity<String> response =
        postWithCookie(
            "/api/rooms",
            host.cookie(),
            "{\"gameId\":\"%s\",\"visibility\":\"PUBLIC\"}".formatted(gameId));
    return UUID.fromString((String) Objects.requireNonNull(parse(response).get("roomId")));
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> contentOf(Map<String, Object> envelope) {
    return (List<Map<String, Object>>) Objects.requireNonNull(envelope.get("content"));
  }

  /**
   * The Postgres container is shared across every test method in this class (Testcontainers
   * restarts it per class, not per method), so an earlier test's rooms are still listable.
   * Narrowing to the game this test seeded keeps each assertion about only its own rooms.
   */
  private static List<Map<String, Object>> roomsForGame(
      List<Map<String, Object>> rows, UUID gameId) {
    return rows.stream().filter(row -> gameId.toString().equals(row.get("gameId"))).toList();
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> mapOf(Map<String, Object> body, String key) {
    return (Map<String, Object>) Objects.requireNonNull(body.get(key));
  }

  private static int numberOf(Map<String, Object> body, String key) {
    return ((Number) Objects.requireNonNull(body.get(key))).intValue();
  }

  /** Two teams of one, so player capacity is exactly two — deterministic for capacity tests. */
  private UUID seedTwoPlayerGame(boolean supportsSpectators, boolean supportsMidGameJoin) {
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
                1024, now(), 2, 2, false, :supportsSpectators, :supportsMidGameJoin, now())
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
        .param("supportsSpectators", supportsSpectators)
        .param("supportsMidGameJoin", supportsMidGameJoin)
        .update();
    jdbcClient
        .sql("update game set active_version_id = :versionId where game_id = :gameId")
        .param("versionId", versionId)
        .param("gameId", gameId)
        .update();
    return gameId;
  }

  private Registered registerUser() throws Exception {
    String username = uniqueUsername();
    ResponseEntity<String> response =
        post(
            "/api/auth/register",
            """
            {"username":"%s","email":"%s","password":"correct horse battery","confirmPassword":"correct horse battery"}
            """
                .formatted(username, uniqueEmail()));
    UUID userId = UUID.fromString((String) parse(response).get("userId"));
    return new Registered(userId, username, sessionCookieHeader(response));
  }

  private ResponseEntity<String> post(String path, String body) {
    return withBody(path, HttpMethod.POST, null, body);
  }

  private ResponseEntity<String> postWithCookie(String path, String cookie, String body) {
    return withBody(path, HttpMethod.POST, cookie, body);
  }

  private ResponseEntity<String> deleteWithCookie(String path, String cookie) {
    return withBody(path, HttpMethod.DELETE, cookie, null);
  }

  private ResponseEntity<String> withBody(
      String path, HttpMethod method, @Nullable String cookie, @Nullable String body) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    if (cookie != null) {
      headers.add(HttpHeaders.COOKIE, cookie);
    }
    HttpEntity<String> request = new HttpEntity<>(body, headers);
    return restTemplate.exchange(baseUrl + path, method, request, String.class);
  }

  private ResponseEntity<String> getWithCookie(String path, String cookie) {
    HttpHeaders headers = new HttpHeaders();
    headers.add(HttpHeaders.COOKIE, cookie);
    HttpEntity<Void> request = new HttpEntity<>(headers);
    return restTemplate.exchange(baseUrl + path, HttpMethod.GET, request, String.class);
  }

  private String sessionCookieHeader(ResponseEntity<String> response) {
    List<String> setCookies =
        java.util.Objects.requireNonNull(response.getHeaders().get(HttpHeaders.SET_COOKIE));
    return setCookies.stream()
        .filter(c -> c.startsWith("turnbase_session="))
        .findFirst()
        .orElseThrow()
        .split(";", 2)[0];
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> parse(ResponseEntity<String> response) throws Exception {
    return objectMapper.readValue(response.getBody(), Map.class);
  }

  private static String uniqueUsername() {
    return "user" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
  }

  private static String uniqueEmail() {
    return "user-" + UUID.randomUUID() + "@example.com";
  }

  private record Registered(UUID userId, String username, String cookie) {}

  private static final class NoOpResponseErrorHandler
      implements org.springframework.web.client.ResponseErrorHandler {
    @Override
    public boolean hasError(org.springframework.http.client.ClientHttpResponse response) {
      return false;
    }

    @Override
    public void handleError(org.springframework.http.client.ClientHttpResponse response) {
      // Never called: hasError() always returns false so assertions see the real status.
    }
  }
}
