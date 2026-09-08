package io.turnbase.platform.social;

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
 * {@code IMP-M1-09}'s own Done-when clause, end to end: the FR-FREQ notification coupling on
 * send/accept/decline/cancel, the reverse-request auto-accept (FR-SOC-004), the cap and the
 * block-without-disclosure rejections (FR-SOC-009/021), and the FR-SOC-017 {@code FRIENDS_ONLY}
 * obligation left by {@code IMP-M1-07}.
 */
class SocialControllerIT extends PostgresIntegrationTest {

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final RestTemplate restTemplate = new RestTemplate(new JdkClientHttpRequestFactory());

  private ConfigurableApplicationContext context;
  private JdbcClient jdbcClient;
  private String baseUrl;

  {
    restTemplate.setErrorHandler(new NoOpResponseErrorHandler());
  }

  @BeforeEach
  void bootApplication() {
    context =
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
            "--turnbase.social.friend-cap=2");
    int port = Integer.parseInt(context.getEnvironment().getProperty("local.server.port"));
    baseUrl = "http://127.0.0.1:" + port;
    jdbcClient = context.getBean(JdbcClient.class);
  }

  @AfterEach
  void stopApplication() {
    context.close();
  }

  @Test
  void sendingARequestInsertsAnActionableNotificationForTheRecipient() throws Exception {
    Registered a = registerUser();
    Registered b = registerUser();

    ResponseEntity<String> response = send(a, b.username());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    Map<String, Object> body = parse(response);
    assertThat(body.get("outcome")).isEqualTo("REQUEST_SENT");
    UUID requestId =
        UUID.fromString(
            (String)
                ((Map<?, ?>) Objects.requireNonNull(body.get("friendRequest"))).get("requestId"));

    String type =
        jdbcClient
            .sql("select type from notification where user_id = :userId")
            .param("userId", b.userId())
            .query(String.class)
            .single();
    UUID notificationRequestId =
        jdbcClient
            .sql("select friend_request_id from notification where user_id = :userId")
            .param("userId", b.userId())
            .query(UUID.class)
            .single();
    assertThat(type).isEqualTo("FRIEND_REQUEST");
    assertThat(notificationRequestId).isEqualTo(requestId);
  }

  @Test
  void acceptingDeletesTheRequestAndNotifiesBothSides() throws Exception {
    Registered a = registerUser();
    Registered b = registerUser();
    UUID requestId = requestIdOf(send(a, b.username()));

    ResponseEntity<String> response = accept(b, requestId);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(parse(response).get("username")).isEqualTo(a.username());
    assertThat(requestExists(requestId)).isFalse();
    assertThat(friendshipExists(a.userId(), b.userId())).isTrue();

    List<String> aTypes = notificationTypesFor(a.userId());
    List<String> bTypes = notificationTypesFor(b.userId());
    assertThat(aTypes).contains("FRIEND_REQUEST_ACCEPTED");
    assertThat(bTypes).contains("FRIEND_ADDED");
  }

  @Test
  void decliningDeletesTheRequestAndNotifiesOnlyTheSender() throws Exception {
    Registered a = registerUser();
    Registered b = registerUser();
    UUID requestId = requestIdOf(send(a, b.username()));

    ResponseEntity<String> response =
        deleteWithCookie("/api/friend-requests/" + requestId, b.cookie());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    assertThat(requestExists(requestId)).isFalse();
    assertThat(notificationTypesFor(a.userId())).contains("FRIEND_REQUEST_DECLINED");
    assertThat(notificationTypesFor(b.userId())).doesNotContain("FRIEND_REQUEST_DECLINED");
  }

  @Test
  void cancellingDeletesTheRequestAndCascadesTheRecipientsNotification() throws Exception {
    Registered a = registerUser();
    Registered b = registerUser();
    UUID requestId = requestIdOf(send(a, b.username()));
    assertThat(notificationTypesFor(b.userId())).contains("FRIEND_REQUEST");

    ResponseEntity<String> response =
        deleteWithCookie("/api/friend-requests/" + requestId, a.cookie());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    assertThat(requestExists(requestId)).isFalse();
    assertThat(notificationTypesFor(b.userId())).doesNotContain("FRIEND_REQUEST");
  }

  @Test
  void deletingAnAlreadyResolvedRequestIsIdempotent() throws Exception {
    Registered a = registerUser();
    Registered b = registerUser();
    UUID requestId = requestIdOf(send(a, b.username()));
    accept(b, requestId);

    ResponseEntity<String> response =
        deleteWithCookie("/api/friend-requests/" + requestId, a.cookie());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
  }

  @Test
  void aThirdPartyCannotResolveSomeoneElsesRequest() throws Exception {
    Registered a = registerUser();
    Registered b = registerUser();
    Registered c = registerUser();
    UUID requestId = requestIdOf(send(a, b.username()));

    ResponseEntity<String> response =
        deleteWithCookie("/api/friend-requests/" + requestId, c.cookie());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
  }

  @Test
  void reverseRequestAutoAcceptsWithNoLeftoverRequestRow() throws Exception {
    Registered a = registerUser();
    Registered b = registerUser();
    send(a, b.username());

    ResponseEntity<String> response = send(b, a.username());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    Map<String, Object> body = parse(response);
    assertThat(body.get("outcome")).isEqualTo("FRIENDS");
    assertThat(friendshipExists(a.userId(), b.userId())).isTrue();
    Long remaining =
        jdbcClient
            .sql(
                "select count(*) from friend_request where (sender_id = :a and recipient_id = :b)"
                    + " or (sender_id = :b and recipient_id = :a)")
            .param("a", a.userId())
            .param("b", b.userId())
            .query(Long.class)
            .single();
    assertThat(remaining).isZero();
  }

  @Test
  void sendingToSelfIsRejected() throws Exception {
    Registered a = registerUser();

    ResponseEntity<String> response = send(a, a.username());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
  }

  @Test
  void aDuplicateOutgoingRequestIsAConflict() throws Exception {
    Registered a = registerUser();
    Registered b = registerUser();
    send(a, b.username());

    ResponseEntity<String> response = send(a, b.username());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
  }

  @Test
  void alreadyFriendsIsAConflict() throws Exception {
    Registered a = registerUser();
    Registered b = registerUser();
    accept(b, requestIdOf(send(a, b.username())));

    ResponseEntity<String> response = send(a, b.username());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
  }

  @Test
  void theSenderCapIsEnforced() throws Exception {
    Registered a = registerUser();
    Registered b = registerUser();
    Registered c = registerUser();
    Registered d = registerUser();
    accept(b, requestIdOf(send(a, b.username())));
    accept(c, requestIdOf(send(a, c.username())));

    ResponseEntity<String> response = send(a, d.username());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
  }

  @Test
  void aBlockedRecipientRejectsWithoutDisclosingTheBlock() throws Exception {
    Registered a = registerUser();
    Registered b = registerUser();
    patchWithCookie("/api/users/me/privacy", b.cookie(), "{\"blockFriendRequests\":true}");

    ResponseEntity<String> response = send(a, b.username());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    String detail = String.valueOf(parse(response).get("detail"));
    assertThat(detail.toLowerCase(java.util.Locale.ROOT)).doesNotContain("block");
  }

  @Test
  void searchHidesTheReasonBehindCanSendRequest() throws Exception {
    Registered a = registerUser();
    Registered b = registerUser();
    patchWithCookie("/api/users/me/privacy", b.cookie(), "{\"blockFriendRequests\":true}");

    ResponseEntity<String> response =
        getWithCookie("/api/users?search=" + b.username().substring(0, 4), a.cookie());

    Map<String, Object> body = parse(response);
    List<Map<String, Object>> content =
        (List<Map<String, Object>>) Objects.requireNonNull(body.get("content"));
    Map<String, Object> row =
        content.stream()
            .filter(r -> b.username().equals(r.get("username")))
            .findFirst()
            .orElseThrow();
    assertThat(row.get("canSendRequest")).isEqualTo(false);
  }

  @Test
  void unfriendIsIdempotentAndMutual() throws Exception {
    Registered a = registerUser();
    Registered b = registerUser();
    accept(b, requestIdOf(send(a, b.username())));

    ResponseEntity<String> first =
        deleteWithCookie("/api/users/me/friends/" + b.userId(), a.cookie());
    ResponseEntity<String> second =
        deleteWithCookie("/api/users/me/friends/" + b.userId(), a.cookie());

    assertThat(first.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    assertThat(second.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    assertThat(friendshipExists(a.userId(), b.userId())).isFalse();
  }

  @Test
  void friendsOnlyVisibilityResolvesAgainstAnActualFriendship() throws Exception {
    Registered owner = registerUser();
    Registered stranger = registerUser();
    Registered friend = registerUser();
    patchWithCookie(
        "/api/users/me/privacy", owner.cookie(), "{\"friendsVisibility\":\"FRIENDS_ONLY\"}");
    accept(friend, requestIdOf(send(owner, friend.username())));

    Map<String, Object> strangerView =
        parse(getWithCookie("/api/users/" + owner.username(), stranger.cookie()));
    Map<String, Object> friendView =
        parse(getWithCookie("/api/users/" + owner.username(), friend.cookie()));

    assertThat((List<String>) strangerView.get("visibleSections")).doesNotContain("friends");
    assertThat((List<String>) friendView.get("visibleSections")).contains("friends");
    assertThat(strangerView.get("relationshipState")).isEqualTo("NONE");
    assertThat(friendView.get("relationshipState")).isEqualTo("FRIENDS");
  }

  @Test
  void relationshipStateReflectsAPendingRequestInEitherDirection() throws Exception {
    Registered a = registerUser();
    Registered b = registerUser();
    send(a, b.username());

    Map<String, Object> aView = parse(getWithCookie("/api/users/" + b.username(), a.cookie()));
    Map<String, Object> bView = parse(getWithCookie("/api/users/" + a.username(), b.cookie()));

    assertThat(aView.get("relationshipState")).isEqualTo("REQUEST_SENT");
    assertThat(bView.get("relationshipState")).isEqualTo("REQUEST_RECEIVED");
  }

  @Test
  void guestsAreForbiddenFromEveryEndpoint() throws Exception {
    ResponseEntity<String> guestResponse = post("/api/auth/guest", null);
    String guestCookie = sessionCookieHeader(guestResponse);
    Registered target = registerUser();

    ResponseEntity<String> response = send(guestCookie, target.username());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
  }

  @Test
  void manyRandomPairsAllInsertSuccessfully() throws Exception {
    // Guards session 33's UUID.compareTo-vs-Postgres-byte-order bug for this table's own
    // canonicalOrder helper: a wrong ordering fails the friendship_order_ck constraint outright.
    for (int i = 0; i < 10; i++) {
      Registered x = registerUser();
      Registered y = registerUser();
      ResponseEntity<String> response = accept(y, requestIdOf(send(x, y.username())));
      assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
  }

  private UUID requestIdOf(ResponseEntity<String> sendResponse) throws Exception {
    Map<String, Object> body = parse(sendResponse);
    return UUID.fromString(
        (String) ((Map<?, ?>) Objects.requireNonNull(body.get("friendRequest"))).get("requestId"));
  }

  private ResponseEntity<String> send(Registered sender, String recipientUsername) {
    return send(sender.cookie(), recipientUsername);
  }

  private ResponseEntity<String> send(String cookie, String recipientUsername) {
    return postWithCookie(
        "/api/friend-requests",
        cookie,
        "{\"recipientUsername\":\"%s\"}".formatted(recipientUsername));
  }

  private ResponseEntity<String> accept(Registered recipient, UUID requestId) {
    return postWithCookie(
        "/api/friend-requests/" + requestId + "/accept", recipient.cookie(), "{}");
  }

  private boolean requestExists(UUID requestId) {
    Long count =
        jdbcClient
            .sql("select count(*) from friend_request where request_id = :requestId")
            .param("requestId", requestId)
            .query(Long.class)
            .single();
    return count != null && count > 0;
  }

  private boolean friendshipExists(UUID a, UUID b) {
    Long count =
        jdbcClient
            .sql(
                "select count(*) from friendship where (user_id_low = :a and user_id_high = :b) or"
                    + " (user_id_low = :b and user_id_high = :a)")
            .param("a", a)
            .param("b", b)
            .query(Long.class)
            .single();
    return count != null && count > 0;
  }

  @SuppressWarnings("unchecked")
  private List<String> notificationTypesFor(UUID userId) {
    return jdbcClient
        .sql("select type from notification where user_id = :userId")
        .param("userId", userId)
        .query(String.class)
        .list();
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

  private ResponseEntity<String> post(String path, @Nullable String body) {
    return withBody(path, HttpMethod.POST, null, body);
  }

  private ResponseEntity<String> postWithCookie(String path, String cookie, String body) {
    return withBody(path, HttpMethod.POST, cookie, body);
  }

  private ResponseEntity<String> patchWithCookie(String path, String cookie, String body) {
    return withBody(path, HttpMethod.PATCH, cookie, body);
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

  private ResponseEntity<String> getWithCookie(String path, @Nullable String cookie) {
    HttpHeaders headers = new HttpHeaders();
    if (cookie != null) {
      headers.add(HttpHeaders.COOKIE, cookie);
    }
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
