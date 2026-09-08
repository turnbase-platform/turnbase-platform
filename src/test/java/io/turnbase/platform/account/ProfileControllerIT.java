package io.turnbase.platform.account;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.turnbase.platform.PostgresIntegrationTest;
import io.turnbase.platform.TurnBaseApplication;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
 * {@code IMP-M1-07}'s own Done-when clause, end to end: server-side privacy resolution with the
 * owner exempt, the FR-ACCT-009 deleted placeholder, the FR-GUEST-010 empty guest profile, and the
 * FR-ACCT-002/003 rolling windows enforced against a stored timestamp.
 */
class ProfileControllerIT extends PostgresIntegrationTest {

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
            "--turnbase.admin.password=admin");
    int port = Integer.parseInt(context.getEnvironment().getProperty("local.server.port"));
    baseUrl = "http://127.0.0.1:" + port;
    jdbcClient = context.getBean(JdbcClient.class);
  }

  @AfterEach
  void stopApplication() {
    context.close();
  }

  @Test
  void ownerSeesAllSectionsEvenWhenPrivate() throws Exception {
    Registered user = registerUser();
    patchWithCookie("/api/users/me/privacy", user.cookie(), "{\"profilePrivate\":true}");

    ResponseEntity<String> response = getWithCookie("/api/users/" + user.username(), user.cookie());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    Map<String, Object> body = parse(response);
    assertThat(body.get("profilePrivate")).isEqualTo(true);
    assertThat(sections(body))
        .containsExactlyInAnyOrder("gameHistory", "statistics", "friends", "uploadedGames");
  }

  @Test
  void aPrivateProfileShowsNoSectionsToAnotherViewer() throws Exception {
    Registered owner = registerUser();
    patchWithCookie("/api/users/me/privacy", owner.cookie(), "{\"profilePrivate\":true}");
    Registered viewer = registerUser();

    ResponseEntity<String> response =
        getWithCookie("/api/users/" + owner.username(), viewer.cookie());

    Map<String, Object> body = parse(response);
    assertThat(body.get("profilePrivate")).isEqualTo(true);
    assertThat(sections(body)).isEmpty();
  }

  @Test
  void aPublicProfileHidesOnlyItsHiddenSections() throws Exception {
    Registered owner = registerUser();
    patchWithCookie(
        "/api/users/me/privacy",
        owner.cookie(),
        "{\"statsVisibility\":\"HIDDEN\",\"friendsVisibility\":\"HIDDEN\"}");
    Registered viewer = registerUser();

    ResponseEntity<String> response =
        getWithCookie("/api/users/" + owner.username(), viewer.cookie());

    Map<String, Object> body = parse(response);
    assertThat(body.get("profilePrivate")).isEqualTo(false);
    assertThat(sections(body)).containsExactlyInAnyOrder("gameHistory", "uploadedGames");
  }

  @Test
  void anUnauthenticatedViewerCanReadAPublicProfile() throws Exception {
    Registered owner = registerUser();

    ResponseEntity<String> response = getWithCookie("/api/users/" + owner.username(), null);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(parse(response).get("username")).isEqualTo(owner.username());
  }

  @Test
  void aGuestProfileShowsOnlyTheUsername() throws Exception {
    ResponseEntity<String> guestResponse = post("/api/auth/guest", null);
    String guestUsername = (String) parse(guestResponse).get("username");

    ResponseEntity<String> response = getWithCookie("/api/users/" + guestUsername, null);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    Map<String, Object> body = parse(response);
    assertThat(body).containsOnlyKeys("username");
    assertThat(body.get("username")).isEqualTo(guestUsername);
  }

  @Test
  void aDeletedUsersProfileReturnsThePlaceholderNotA404() throws Exception {
    Registered user = registerUser();
    jdbcClient
        .sql(
            "update app_user set deleted_at = now(), deletion_reason = 'SELF_SERVICE' where"
                + " user_id = :id")
        .param("id", user.userId())
        .update();

    ResponseEntity<String> response = getWithCookie("/api/users/" + user.username(), null);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    Map<String, Object> body = parse(response);
    assertThat(body).containsOnlyKeys("username", "deleted");
    assertThat(body.get("deleted")).isEqualTo(true);
  }

  @Test
  void anUnknownUsernameIs404() {
    ResponseEntity<String> response = getWithCookie("/api/users/nobody-" + UUID.randomUUID(), null);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void updatingTheUsernameChangesItAndStampsTheWindow() throws Exception {
    Registered user = registerUser();
    String newUsername = uniqueUsername();

    ResponseEntity<String> response =
        patchWithCookie(
            "/api/users/me", user.cookie(), "{\"username\":\"%s\"}".formatted(newUsername));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(parse(response).get("username")).isEqualTo(newUsername);
    Instant changedAt =
        jdbcClient
            .sql("select username_changed_at from app_user where user_id = :id")
            .param("id", user.userId())
            .query(Instant.class)
            .single();
    assertThat(changedAt).isNotNull();
  }

  @Test
  void aSecondUsernameChangeWithinTheWindowIsRejected() throws Exception {
    Registered user = registerUser();
    patchWithCookie(
        "/api/users/me", user.cookie(), "{\"username\":\"%s\"}".formatted(uniqueUsername()));

    ResponseEntity<String> response =
        patchWithCookie(
            "/api/users/me", user.cookie(), "{\"username\":\"%s\"}".formatted(uniqueUsername()));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
  }

  @Test
  void changingTheUsernameToADuplicateIsAConflict() throws Exception {
    Registered first = registerUser();
    Registered second = registerUser();

    ResponseEntity<String> response =
        patchWithCookie(
            "/api/users/me", second.cookie(), "{\"username\":\"%s\"}".formatted(first.username()));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
  }

  @Test
  void changingThePasswordWithTheWrongCurrentPasswordIsRejected() throws Exception {
    Registered user = registerUser();

    ResponseEntity<String> response =
        postWithCookie(
            "/api/users/me/password",
            user.cookie(),
            """
            {"currentPassword":"wrong password","newPassword":"new password 1","confirmNewPassword":"new password 1"}
            """);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
  }

  @Test
  void changingThePasswordWithTheCorrectCurrentPasswordSucceedsAndTheNewPasswordLogsIn()
      throws Exception {
    Registered user = registerUser();

    ResponseEntity<String> response =
        postWithCookie(
            "/api/users/me/password",
            user.cookie(),
            """
            {"currentPassword":"correct horse battery","newPassword":"new password 1","confirmNewPassword":"new password 1"}
            """);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

    ResponseEntity<String> loginResponse =
        post(
            "/api/auth/login",
            """
            {"usernameOrEmail":"%s","password":"new password 1"}
            """
                .formatted(user.username()));
    assertThat(loginResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
  }

  @Test
  void aGuestCannotPatchPrivacySettings() throws Exception {
    ResponseEntity<String> guestResponse = post("/api/auth/guest", null);
    String cookie = sessionCookieHeader(guestResponse);

    ResponseEntity<String> response =
        patchWithCookie("/api/users/me/privacy", cookie, "{\"profilePrivate\":true}");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
  }

  @SuppressWarnings("unchecked")
  private static List<String> sections(Map<String, Object> body) {
    Object raw = body.get("visibleSections");
    return raw == null ? List.of() : (List<String>) raw;
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
