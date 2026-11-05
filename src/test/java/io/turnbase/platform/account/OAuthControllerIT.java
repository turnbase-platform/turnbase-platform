package io.turnbase.platform.account;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import io.turnbase.platform.PostgresIntegrationTest;
import io.turnbase.platform.TurnBaseApplication;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code oauth-rest-api.md} — the full round trip against a real application boot and Postgres, a
 * stub Google (a plain JDK {@link HttpServer}, same reasoning as {@code GoogleOAuthClientTest}),
 * and no real browser: this suite drives {@code /auth/google} to obtain a real signed {@code
 * state}, then calls {@code /auth/callback} directly with it, the same way a browser would after
 * Google's own redirect.
 *
 * <p>Keeps its own boot: the stub Google port is in the boot arguments, so no two runs share a key.
 * (IMP-M20-10)
 */
class OAuthControllerIT extends PostgresIntegrationTest {

  private final JsonMapper objectMapper = JsonMapper.builder().build();
  private final HttpClient httpClient =
      HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

  private HttpServer googleStub;
  private volatile String userInfoResponse =
      "{\"sub\":\"google-sub-1\",\"email\":\"alice@example.com\",\"email_verified\":true}";
  private volatile int tokenStatus = 200;

  private ConfigurableApplicationContext context;
  private JdbcClient jdbcClient;
  private String baseUrl;

  @BeforeEach
  void bootStubAndApplication() throws IOException {
    googleStub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    googleStub.createContext(
        "/token",
        exchange -> {
          int status = tokenStatus;
          respond(
              exchange,
              status,
              status == 200
                  ? "{\"access_token\":\"test-access-token\",\"token_type\":\"Bearer\"}"
                  : "{\"error\":\"invalid_grant\"}");
        });
    googleStub.createContext("/userinfo", exchange -> respond(exchange, 200, userInfoResponse));
    googleStub.start();
    int stubPort = googleStub.getAddress().getPort();

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
            "--spring.data.redis.url=" + redisUrl(),
            "--turnbase.oauth.google.client-id=test-client-id",
            "--turnbase.oauth.google.client-secret=test-client-secret",
            "--turnbase.oauth.google.authorization-endpoint=http://127.0.0.1:"
                + stubPort
                + "/authorize",
            "--turnbase.oauth.google.token-endpoint=http://127.0.0.1:" + stubPort + "/token",
            "--turnbase.oauth.google.userinfo-endpoint=http://127.0.0.1:" + stubPort + "/userinfo",
            // This class drives /auth/google and /auth/oauth/complete-registration repeatedly
            // from one IP; real budgets would fail unrelated later tests (same reasoning as
            // AuthControllerIT).
            "--turnbase.rate-limit.auth.failures-before-lockout=1000000",
            "--turnbase.rate-limit.minting.registration.capacity=1000000",
            "--turnbase.rate-limit.public.capacity=1000000");
    int port = Integer.parseInt(context.getEnvironment().getProperty("local.server.port"));
    baseUrl = "http://127.0.0.1:" + port;
    jdbcClient = context.getBean(JdbcClient.class);
  }

  @AfterEach
  void stopApplicationAndStub() {
    context.close();
    googleStub.stop(0);
  }

  private void resetGoogleIdentity(String sub, String email) {
    userInfoResponse =
        "{\"sub\":\"%s\",\"email\":\"%s\",\"email_verified\":true}".formatted(sub, email);
  }

  @Test
  void providersReportsGoogleAvailableWithoutASession() throws Exception {
    HttpResponse<String> response = get("/api/auth/providers", null);

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(parse(response)).containsExactly(Map.entry("google", true));
  }

  @Test
  void initiateRedirectsToTheAuthorizationEndpointCarryingASignedState() throws Exception {
    HttpResponse<Void> response = getNoRedirect("/auth/google?redirect=/rooms/abcd");

    assertThat(response.statusCode()).isEqualTo(HttpStatus.FOUND.value());
    String location = response.headers().firstValue("Location").orElseThrow();
    assertThat(location)
        .startsWith("http://127.0.0.1:" + googleStub.getAddress().getPort() + "/authorize?");
    assertThat(location).contains("client_id=test-client-id");
  }

  @Test
  void aFirstTimeGoogleLoginStartsPendingRegistrationRatherThanCreatingAnAccount()
      throws Exception {
    resetGoogleIdentity(
        "google-sub-" + UUID.randomUUID(), "user-" + UUID.randomUUID() + "@example.com");
    String state = stateFrom(getNoRedirect("/auth/google"));

    HttpResponse<Void> callback = getNoRedirect("/auth/callback?code=auth-code&state=" + state);

    assertThat(callback.statusCode()).isEqualTo(HttpStatus.FOUND.value());
    assertThat(callback.headers().firstValue("Location")).contains("/auth/complete-registration");
    String pendingCookie = cookieNamed(callback, "turnbase_oauth_pending");
    assertThat(pendingCookie).contains("HttpOnly").contains("Secure").contains("SameSite=Strict");
    assertThat(callback.headers().allValues(HttpHeaders.SET_COOKIE))
        .noneMatch(c -> c.startsWith("turnbase_session="));
  }

  @Test
  void completingRegistrationCreatesTheAccountAndIssuesASession() throws Exception {
    String email = "user-" + UUID.randomUUID() + "@example.com";
    resetGoogleIdentity("google-sub-" + UUID.randomUUID(), email);
    String state = stateFrom(getNoRedirect("/auth/google"));
    HttpResponse<Void> callback = getNoRedirect("/auth/callback?code=auth-code&state=" + state);
    String pendingCookie = cookieValue(callback, "turnbase_oauth_pending");
    String username = "oauth" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);

    HttpResponse<String> pending = get("/api/auth/oauth/pending", pendingCookie);
    assertThat(pending.statusCode()).isEqualTo(200);
    assertThat(parse(pending).get("email")).isEqualTo(email);

    HttpResponse<String> complete =
        post(
            "/api/auth/oauth/complete-registration",
            "{\"username\":\"%s\"}".formatted(username),
            pendingCookie);

    assertThat(complete.statusCode()).isEqualTo(201);
    assertThat(parse(complete).get("kind")).isEqualTo("REGISTERED");
    assertThat(complete.headers().firstValue("Location")).contains("/api/users/" + username);
    assertThat(complete.headers().allValues(HttpHeaders.SET_COOKIE))
        .anyMatch(c -> c.startsWith("turnbase_session="))
        .anyMatch(c -> c.startsWith("turnbase_oauth_pending=") && c.contains("Max-Age=0"));

    Map<String, Object> row =
        jdbcClient
            .sql("select kind, email, email_source from app_user where username = :username")
            .param("username", username)
            .query()
            .singleRow();
    assertThat(row.get("kind")).isEqualTo("REGISTERED");
    assertThat(row.get("email")).isEqualTo(email);
    assertThat(row.get("email_source")).isEqualTo("OAUTH");

    Long googleMethodCount =
        jdbcClient
            .sql(
                "select count(*) from user_auth_method m join app_user u on u.user_id = m.user_id"
                    + " where u.username = :username and m.method = 'GOOGLE'")
            .param("username", username)
            .query(Long.class)
            .single();
    assertThat(googleMethodCount).isEqualTo(1L);
  }

  @Test
  void aReturningGoogleLoginIssuesASessionDirectlyWithoutAPendingCookie() throws Exception {
    resetGoogleIdentity(
        "google-sub-" + UUID.randomUUID(), "user-" + UUID.randomUUID() + "@example.com");
    String username = "oauth" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    String state1 = stateFrom(getNoRedirect("/auth/google"));
    HttpResponse<Void> callback1 = getNoRedirect("/auth/callback?code=auth-code&state=" + state1);
    String pendingCookie = cookieValue(callback1, "turnbase_oauth_pending");
    post(
        "/api/auth/oauth/complete-registration",
        "{\"username\":\"%s\"}".formatted(username),
        pendingCookie);

    String state2 = stateFrom(getNoRedirect("/auth/google?redirect=/rooms/xyz"));
    HttpResponse<Void> callback2 = getNoRedirect("/auth/callback?code=auth-code&state=" + state2);

    assertThat(callback2.statusCode()).isEqualTo(HttpStatus.FOUND.value());
    assertThat(callback2.headers().firstValue("Location")).contains("/rooms/xyz");
    assertThat(callback2.headers().allValues(HttpHeaders.SET_COOKIE))
        .anyMatch(c -> c.startsWith("turnbase_session="));
  }

  @Test
  void anEmailCollisionChallengesForTheExistingAccountAndLinksOnSuccess() throws Exception {
    String existingUsername =
        "cred" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    String existingEmail = "collide-" + UUID.randomUUID() + "@example.com";
    String password = "correct horse battery";
    ResponseSummary registerResponse =
        registerCredentialAccount(existingUsername, existingEmail, password);
    UUID existingUserId = UUID.fromString((String) registerResponse.body().get("userId"));

    resetGoogleIdentity("google-sub-2", existingEmail);
    String state = stateFrom(getNoRedirect("/auth/google"));
    HttpResponse<Void> callback = getNoRedirect("/auth/callback?code=auth-code&state=" + state);
    String pendingCookie = cookieValue(callback, "turnbase_oauth_pending");

    HttpResponse<String> complete =
        post("/api/auth/oauth/complete-registration", "{\"username\":\"whatever\"}", pendingCookie);
    assertThat(complete.statusCode()).isEqualTo(200);
    assertThat(parse(complete).get("outcome")).isEqualTo("CHALLENGE_REQUIRED");

    HttpResponse<String> wrongChallenge =
        post("/api/auth/oauth/link-challenge", "{\"password\":\"wrong\"}", pendingCookie);
    assertThat(wrongChallenge.statusCode()).isEqualTo(401);

    HttpResponse<String> rightChallenge =
        post(
            "/api/auth/oauth/link-challenge",
            "{\"password\":\"%s\"}".formatted(password),
            pendingCookie);
    assertThat(rightChallenge.statusCode()).isEqualTo(200);
    assertThat(parse(rightChallenge).get("outcome")).isEqualTo("LINKED");
    assertThat(rightChallenge.headers().allValues(HttpHeaders.SET_COOKIE))
        .anyMatch(c -> c.startsWith("turnbase_session="));

    Long googleMethodCount =
        jdbcClient
            .sql(
                "select count(*) from user_auth_method where user_id = :userId and method = 'GOOGLE'")
            .param("userId", existingUserId)
            .query(Long.class)
            .single();
    assertThat(googleMethodCount).isEqualTo(1L);
  }

  private ResponseSummary registerCredentialAccount(String username, String email, String password)
      throws Exception {
    HttpResponse<String> response =
        postJson(
            "/api/auth/register",
            """
            {"username":"%s","email":"%s","password":"%s","confirmPassword":"%s"}
            """
                .formatted(username, email, password, password),
            null);
    return new ResponseSummary(response.statusCode(), parse(response));
  }

  private record ResponseSummary(int status, Map<String, Object> body) {}

  private String stateFrom(HttpResponse<Void> initiateResponse) {
    String location = initiateResponse.headers().firstValue("Location").orElseThrow();
    String query = URI.create(location).getRawQuery();
    for (String param : query.split("&")) {
      String[] parts = param.split("=", 2);
      if ("state".equals(parts[0])) {
        return parts[1];
      }
    }
    throw new IllegalStateException("no state parameter in " + location);
  }

  private String cookieValue(HttpResponse<Void> response, String name) {
    String nameValue = cookieNamed(response, name).split(";", 2)[0];
    return nameValue.substring(name.length() + 1);
  }

  private String cookieNamed(HttpResponse<Void> response, String name) {
    return response.headers().allValues(HttpHeaders.SET_COOKIE).stream()
        .filter(c -> c.startsWith(name + "="))
        .findFirst()
        .orElseThrow();
  }

  private HttpResponse<Void> getNoRedirect(String path) throws Exception {
    HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path)).GET().build();
    return httpClient.send(request, HttpResponse.BodyHandlers.discarding());
  }

  private HttpResponse<String> get(String path, @Nullable String cookie) throws Exception {
    HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path)).GET();
    if (cookie != null) {
      builder.header("Cookie", "turnbase_oauth_pending=" + cookie);
    }
    return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> post(String path, String jsonBody, @Nullable String pendingCookie)
      throws Exception {
    return postJson(path, jsonBody, pendingCookie);
  }

  private HttpResponse<String> postJson(
      String path, String jsonBody, @Nullable String pendingCookie) throws Exception {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(URI.create(baseUrl + path))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(jsonBody));
    if (pendingCookie != null) {
      builder.header("Cookie", "turnbase_oauth_pending=" + pendingCookie);
    }
    return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

  private Map<String, Object> parse(HttpResponse<String> response) throws Exception {
    return objectMapper.readValue(response.body(), Map.class);
  }

  private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body)
      throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    try (OutputStream os = exchange.getResponseBody()) {
      os.write(bytes);
    }
  }
}
