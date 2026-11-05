package io.turnbase.platform.room;

import static org.assertj.core.api.Assertions.assertThat;

import io.turnbase.platform.PostgresIntegrationTest;
import io.turnbase.platform.SharedApplication;
import io.turnbase.platform.UsesSharedApplication;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code room-invitation-rest-api.md} §§2-4 end to end: send (host-only, friends-only, idempotent),
 * decline (self-scoped, idempotent), and acceptance via the ordinary join endpoint (resolves on
 * success, untouched on failure — {@code IMP-M2-12}'s own Done-when).
 */
@UsesSharedApplication
class RoomInvitationControllerIT extends PostgresIntegrationTest {

  private final JsonMapper objectMapper = JsonMapper.builder().build();
  private final RestTemplate restTemplate = new RestTemplate(new JdkClientHttpRequestFactory());

  private @Nullable ConfigurableApplicationContext context;
  private JdbcClient jdbcClient;
  private String baseUrl;

  {
    restTemplate.setErrorHandler(new NoOpResponseErrorHandler());
  }

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
            "--turnbase.room.spectator-capacity=2",
            "--spring.data.redis.url=" + redisUrl(),
            "--turnbase.rate-limit.auth.failures-before-lockout=1000000",
            "--turnbase.rate-limit.minting.guest.capacity=1000000",
            "--turnbase.rate-limit.minting.registration.capacity=1000000");
    context = booted;
    int port = Integer.parseInt(booted.getEnvironment().getProperty("local.server.port"));
    baseUrl = "http://127.0.0.1:" + port;
    jdbcClient = booted.getBean(JdbcClient.class);
  }

  @Test
  void sendInsertsAnInvitationAndARoomInvitationNotification() throws Exception {
    Registered host = registerUser();
    Registered friend = registerUser();
    befriend(host, friend);
    UUID roomId = createRoom(host);

    ResponseEntity<String> response = invite(roomId, host.cookie(), friend.username());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    Map<String, Object> body = parse(response);
    assertThat(body.get("inviteeUsername")).isEqualTo(friend.username());
    UUID invitationId = UUID.fromString((String) body.get("invitationId"));

    Long invitationRows =
        jdbcClient
            .sql("select count(*) from room_invitation where invitation_id = :id")
            .param("id", invitationId)
            .query(Long.class)
            .single();
    assertThat(invitationRows).isEqualTo(1);

    NotificationRow notification =
        jdbcClient
            .sql("select type, user_id from notification where invitation_id = :id")
            .param("id", invitationId)
            .query(
                (rs, rowNum) ->
                    new NotificationRow(rs.getString("type"), rs.getObject("user_id", UUID.class)))
            .single();
    assertThat(notification.type()).isEqualTo("ROOM_INVITATION");
    assertThat(notification.userId()).isEqualTo(friend.userId());
  }

  private record NotificationRow(String type, UUID userId) {}

  @Test
  void sendByANonHostIsForbidden() throws Exception {
    Registered host = registerUser();
    Registered other = registerUser();
    Registered friend = registerUser();
    befriend(other, friend);
    UUID roomId = createRoom(host);

    ResponseEntity<String> response = invite(roomId, other.cookie(), friend.username());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
  }

  @Test
  void sendToANonFriendIsRejected() throws Exception {
    Registered host = registerUser();
    Registered stranger = registerUser();
    UUID roomId = createRoom(host);

    ResponseEntity<String> response = invite(roomId, host.cookie(), stranger.username());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
  }

  @Test
  void sendToSelfIsRejected() throws Exception {
    Registered host = registerUser();
    UUID roomId = createRoom(host);

    ResponseEntity<String> response = invite(roomId, host.cookie(), host.username());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
  }

  @Test
  void sendingTwiceToTheSameFriendIsIdempotent() throws Exception {
    Registered host = registerUser();
    Registered friend = registerUser();
    befriend(host, friend);
    UUID roomId = createRoom(host);

    Map<String, Object> first = parse(invite(roomId, host.cookie(), friend.username()));
    Map<String, Object> second = parse(invite(roomId, host.cookie(), friend.username()));

    assertThat(second.get("invitationId")).isEqualTo(first.get("invitationId"));
    Long count =
        jdbcClient
            .sql("select count(*) from room_invitation where room_id = :roomId")
            .param("roomId", roomId)
            .query(Long.class)
            .single();
    assertThat(count).isEqualTo(1);
  }

  @Test
  void declineDeletesTheInvitationAndItsNotification() throws Exception {
    Registered host = registerUser();
    Registered friend = registerUser();
    befriend(host, friend);
    UUID roomId = createRoom(host);
    UUID invitationId =
        UUID.fromString(
            (String) parse(invite(roomId, host.cookie(), friend.username())).get("invitationId"));

    ResponseEntity<String> response =
        deleteWithCookie("/api/rooms/" + roomId + "/invitations/me", friend.cookie());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    Long invitationRows =
        jdbcClient
            .sql("select count(*) from room_invitation where invitation_id = :id")
            .param("id", invitationId)
            .query(Long.class)
            .single();
    assertThat(invitationRows).isEqualTo(0);
    Long notificationRows =
        jdbcClient
            .sql("select count(*) from notification where invitation_id = :id")
            .param("id", invitationId)
            .query(Long.class)
            .single();
    assertThat(notificationRows).isEqualTo(0);
  }

  @Test
  void declineWithNoMatchingInvitationIsStillNoContent() throws Exception {
    Registered host = registerUser();
    Registered friend = registerUser();
    UUID roomId = createRoom(host);

    ResponseEntity<String> response =
        deleteWithCookie("/api/rooms/" + roomId + "/invitations/me", friend.cookie());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
  }

  @Test
  void aSuccessfulJoinResolvesThePendingInvitationToThatRoom() throws Exception {
    Registered host = registerUser();
    Registered friend = registerUser();
    befriend(host, friend);
    UUID roomId = createRoom(host);
    UUID invitationId =
        UUID.fromString(
            (String) parse(invite(roomId, host.cookie(), friend.username())).get("invitationId"));

    ResponseEntity<String> joinResponse =
        postWithCookie(
            "/api/rooms/" + roomId + "/participants", friend.cookie(), "{\"role\":\"PLAYER\"}");

    assertThat(joinResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    Long invitationRows =
        jdbcClient
            .sql("select count(*) from room_invitation where invitation_id = :id")
            .param("id", invitationId)
            .query(Long.class)
            .single();
    assertThat(invitationRows).isEqualTo(0);
  }

  @Test
  void aFailedJoinLeavesThePendingInvitationUntouched() throws Exception {
    Registered host = registerUser();
    Registered filler = registerUser();
    Registered friend = registerUser();
    befriend(host, friend);
    UUID roomId = createRoom(host);
    postWithCookie(
        "/api/rooms/" + roomId + "/participants", filler.cookie(), "{\"role\":\"PLAYER\"}");
    UUID invitationId =
        UUID.fromString(
            (String) parse(invite(roomId, host.cookie(), friend.username())).get("invitationId"));

    ResponseEntity<String> joinResponse =
        postWithCookie(
            "/api/rooms/" + roomId + "/participants", friend.cookie(), "{\"role\":\"PLAYER\"}");

    assertThat(joinResponse.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    Long invitationRows =
        jdbcClient
            .sql("select count(*) from room_invitation where invitation_id = :id")
            .param("id", invitationId)
            .query(Long.class)
            .single();
    assertThat(invitationRows).isEqualTo(1);
  }

  private ResponseEntity<String> invite(UUID roomId, String hostCookie, String inviteeUsername) {
    return postWithCookie(
        "/api/rooms/" + roomId + "/invitations",
        hostCookie,
        "{\"inviteeUsername\":\"%s\"}".formatted(inviteeUsername));
  }

  private void befriend(Registered a, Registered b) {
    jdbcClient
        .sql(
            "insert into friendship (user_id_low, user_id_high) values (least(:a, :b), greatest(:a, :b))")
        .param("a", a.userId())
        .param("b", b.userId())
        .update();
  }

  private UUID createRoom(Registered host) throws Exception {
    UUID gameId = seedTwoPlayerGame();
    Map<String, Object> created =
        parse(
            postWithCookie(
                "/api/rooms",
                host.cookie(),
                "{\"gameId\":\"%s\",\"visibility\":\"PUBLIC\"}".formatted(gameId)));
    return UUID.fromString((String) created.get("roomId"));
  }

  private UUID seedTwoPlayerGame() {
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
                1024, now(), 2, 2, false, false, false, now())
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
    public void handleError(
        java.net.URI url,
        org.springframework.http.HttpMethod method,
        org.springframework.http.client.ClientHttpResponse response) {
      // Never called: hasError() always returns false so assertions see the real status.
    }
  }
}
