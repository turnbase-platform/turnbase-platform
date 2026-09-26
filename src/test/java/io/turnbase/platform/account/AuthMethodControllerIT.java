package io.turnbase.platform.account;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@code IMP-M1-11}'s own Done-when clause, end to end: the account representation, the three
 * {@code /api/users/me/auth-methods} routes, FR-AUTH-011's guard under real concurrency, and the
 * three-request Google link ({@code oauth-rest-api.md} §7a) against a stub Google.
 */
class AuthMethodControllerIT extends PostgresIntegrationTest {

  private static final String PASSWORD = "correct horse battery";

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final HttpClient httpClient =
      HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

  private HttpServer googleStub;
  private volatile String userInfoResponse = "";

  private ConfigurableApplicationContext context;
  private JdbcClient jdbcClient;
  private StringRedisTemplate redis;
  private String baseUrl;

  @BeforeEach
  void bootStubAndApplication() throws IOException {
    googleStub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    googleStub.createContext(
        "/token",
        exchange ->
            respond(
                exchange, "{\"access_token\":\"test-access-token\",\"token_type\":\"Bearer\"}"));
    googleStub.createContext("/userinfo", exchange -> respond(exchange, userInfoResponse));
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
            // Many registrations, logins and deliberate failures from one IP across this class;
            // the real Tier 1/2/4/5 budgets are RateLimitInterceptorIT's to assert.
            "--turnbase.rate-limit.auth.failures-before-lockout=1000000",
            "--turnbase.rate-limit.minting.guest.capacity=1000000",
            "--turnbase.rate-limit.minting.registration.capacity=1000000",
            "--turnbase.rate-limit.public.capacity=1000000",
            "--turnbase.rate-limit.api.capacity=1000000");
    int port = Integer.parseInt(context.getEnvironment().getProperty("local.server.port"));
    baseUrl = "http://127.0.0.1:" + port;
    jdbcClient = context.getBean(JdbcClient.class);
    redis = context.getBean(StringRedisTemplate.class);
  }

  @AfterEach
  void stopApplicationAndStub() {
    context.close();
    googleStub.stop(0);
  }

  // ---- GET /api/users/me ----

  @Test
  void theAccountRepresentationCarriesNoSecretsAndNullWindowsWhenChangeableNow() throws Exception {
    Account user = registerCredentialAccount();

    HttpResponse<String> response = send("GET", "/api/users/me", user.cookie(), null);

    assertThat(response.statusCode()).isEqualTo(200);
    Map<String, Object> body = parse(response);
    assertThat(body.get("userId")).isEqualTo(user.userId().toString());
    assertThat(body.get("username")).isEqualTo(user.username());
    assertThat(body.get("email")).isEqualTo(user.email());
    assertThat(body.get("emailSource")).isEqualTo("SELF");
    assertThat(body.get("authMethods")).isEqualTo(List.of("PASSWORD"));
    assertThat(body).containsKey("privacy");
    assertThat(body.get("usernameChangeableAt")).isNull();
    assertThat(body.get("emailChangeableAt")).isNull();
    assertThat(response.body())
        .doesNotContainIgnoringCase("password_hash")
        .doesNotContainIgnoringCase("passwordHash")
        .doesNotContainIgnoringCase("provider_subject")
        .doesNotContainIgnoringCase("providerSubject");
  }

  @Test
  void usernameChangeableAtIsTheInstantTheWindowElapsesOnceTheUsernameHasChanged()
      throws Exception {
    Account user = registerCredentialAccount();
    HttpResponse<String> patch =
        send(
            "PATCH",
            "/api/users/me",
            user.cookie(),
            "{\"username\":\"%s\"}".formatted(uniqueUsername()));
    assertThat(patch.statusCode()).isEqualTo(200);

    Map<String, Object> body = parse(send("GET", "/api/users/me", user.cookie(), null));

    assertThat(Instant.parse((String) body.get("usernameChangeableAt"))).isAfter(Instant.now());
    assertThat(body.get("emailChangeableAt")).isNull();
  }

  @Test
  void aGuestCannotReadTheAccountRepresentation() throws Exception {
    HttpResponse<String> guest = send("POST", "/api/auth/guest", null, null);

    HttpResponse<String> response = send("GET", "/api/users/me", sessionCookie(guest), null);

    assertThat(response.statusCode()).isEqualTo(403);
  }

  // ---- POST .../password ----

  @Test
  void addingAPasswordToAGoogleOnlyAccountSucceedsOnceAndThenConflicts() throws Exception {
    Account user = registerGoogleAccount();
    assertThat(methodsOf(user)).containsExactly("GOOGLE");

    HttpResponse<String> first = addPassword(user, PASSWORD, PASSWORD);
    HttpResponse<String> second = addPassword(user, PASSWORD, PASSWORD);

    assertThat(first.statusCode()).isEqualTo(204);
    assertThat(methodsOf(user)).containsExactlyInAnyOrder("GOOGLE", "PASSWORD");
    assertThat(second.statusCode()).isEqualTo(409);
  }

  @Test
  void addingAPasswordIsHeldToRegistrationsPolicy() throws Exception {
    Account user = registerGoogleAccount();

    assertThat(addPassword(user, "short", "short").statusCode()).isEqualTo(400);
    assertThat(addPassword(user, PASSWORD, PASSWORD + "x").statusCode()).isEqualTo(400);
    assertThat(methodsOf(user)).containsExactly("GOOGLE");
  }

  @Test
  void aGuestCannotAddAPassword() throws Exception {
    String cookie = sessionCookie(send("POST", "/api/auth/guest", null, null));

    HttpResponse<String> response =
        send(
            "POST",
            "/api/users/me/auth-methods/password",
            cookie,
            "{\"password\":\"%s\",\"confirmPassword\":\"%s\"}".formatted(PASSWORD, PASSWORD));

    assertThat(response.statusCode()).isEqualTo(403);
  }

  // ---- DELETE .../{method} ----

  @Test
  void removingTheOnlyMethodIs422AndLeavesIt() throws Exception {
    Account user = registerCredentialAccount();

    HttpResponse<String> response = removeMethod(user, "PASSWORD");

    assertThat(response.statusCode()).isEqualTo(422);
    assertThat(methodsOf(user)).containsExactly("PASSWORD");
  }

  @Test
  void removingAMethodTheAccountDoesNotHoldIs404AndAnUnknownMethodIs400() throws Exception {
    Account user = registerCredentialAccount();

    assertThat(removeMethod(user, "GOOGLE").statusCode()).isEqualTo(404);
    assertThat(removeMethod(user, "CARRIER_PIGEON").statusCode()).isEqualTo(400);
  }

  @Test
  void removingGoogleFromAnOauthEmailAccountMakesTheEmailSelfSourced() throws Exception {
    Account user = registerGoogleAccount();
    assertThat(emailSourceOf(user)).isEqualTo("OAUTH");
    assertThat(addPassword(user, PASSWORD, PASSWORD).statusCode()).isEqualTo(204);

    HttpResponse<String> response = removeMethod(user, "GOOGLE");

    assertThat(response.statusCode()).isEqualTo(204);
    assertThat(methodsOf(user)).containsExactly("PASSWORD");
    assertThat(emailSourceOf(user)).isEqualTo("SELF");
    assertThat(emailOf(user)).isEqualTo(user.email());
  }

  @Test
  void twoConcurrentRemovalsOnATwoMethodAccountLeaveExactlyOne() throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      // One race can be won by luck; repeat on fresh accounts so an unguarded implementation
      // (both deletes see two rows, both delete a different one) cannot pass by timing.
      for (int round = 0; round < 12; round++) {
        Account user = registerCredentialAccount();
        linkGoogle(user, "google-sub-" + UUID.randomUUID());
        assertThat(methodsOf(user)).hasSize(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        Callable<Integer> removePassword = () -> awaitThenRemove(barrier, user, "PASSWORD");
        Callable<Integer> removeGoogle = () -> awaitThenRemove(barrier, user, "GOOGLE");

        Future<Integer> first = pool.submit(removePassword);
        Future<Integer> second = pool.submit(removeGoogle);

        assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder(204, 422);
        assertThat(methodCount(user)).isEqualTo(1L);
      }
    } finally {
      pool.shutdownNow();
    }
  }

  private int awaitThenRemove(CyclicBarrier barrier, Account user, String method) throws Exception {
    barrier.await();
    return removeMethod(user, method).statusCode();
  }

  // ---- Google link, oauth-rest-api.md §7a ----

  @Test
  void linkIntentWithoutARegisteredSessionRedirectsToLogin() throws Exception {
    HttpResponse<String> anonymous = send("GET", "/auth/google?intent=link", null, null);
    String guestCookie = sessionCookie(send("POST", "/api/auth/guest", null, null));
    HttpResponse<String> guest = send("GET", "/auth/google?intent=link", guestCookie, null);

    for (HttpResponse<String> response : List.of(anonymous, guest)) {
      assertThat(response.statusCode()).isEqualTo(302);
      assertThat(location(response)).startsWith("/login?redirect=").contains("settings");
    }
  }

  @Test
  void theCallbackOnTheLinkBranchStoresOnlyTheLinkRecordAndNeitherIssuesNorReadsASession()
      throws Exception {
    Account user = registerCredentialAccount();
    String state = linkState(user);
    googleIdentity("google-sub-" + UUID.randomUUID(), user.email());

    // No Cookie header at all: Google's cross-site return does not carry a SameSite=Strict one.
    HttpResponse<String> callback = send("GET", callbackPath(state), null, null);

    assertThat(callback.statusCode()).isEqualTo(302);
    assertThat(location(callback)).isEqualTo("/settings?linkGoogle=1");
    List<String> setCookies = callback.headers().allValues(HttpHeaders.SET_COOKIE);
    assertThat(setCookies).noneMatch(c -> c.startsWith("turnbase_session="));
    String linkCookie =
        setCookies.stream()
            .filter(c -> c.startsWith("turnbase_oauth_link="))
            .findFirst()
            .orElseThrow();
    assertThat(linkCookie).contains("HttpOnly").contains("Secure").contains("SameSite=Strict");
    String token = linkCookie.split(";", 2)[0].substring("turnbase_oauth_link=".length());
    assertThat(redis.hasKey("oauth:link:" + token)).isTrue();
    assertThat(methodsOf(user)).containsExactly("PASSWORD");
  }

  @Test
  void confirmingByTheOwnerLinksGoogleAndTouchesNeitherEmailNorItsSource() throws Exception {
    Account user = registerCredentialAccount();
    String linkCookie = linkCookieFor(user, "google-sub-" + UUID.randomUUID(), "other@example.com");

    HttpResponse<String> response = confirmLink(user, linkCookie);

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(parse(response).get("authMethods"))
        .asList()
        .containsExactlyInAnyOrder("PASSWORD", "GOOGLE");
    assertThat(response.headers().allValues(HttpHeaders.SET_COOKIE))
        .anyMatch(c -> c.startsWith("turnbase_oauth_link=") && c.contains("Max-Age=0"));
    assertThat(emailOf(user)).isEqualTo(user.email());
    assertThat(emailSourceOf(user)).isEqualTo("SELF");
  }

  @Test
  void aRecordMintedForOneUserAndConfirmedByAnotherIs403AndIsNotConsumed() throws Exception {
    Account owner = registerCredentialAccount();
    Account intruder = registerCredentialAccount();
    String linkCookie = linkCookieFor(owner, "google-sub-" + UUID.randomUUID(), owner.email());
    String token = linkCookie.substring("turnbase_oauth_link=".length());

    HttpResponse<String> response = confirmLink(intruder, linkCookie);

    assertThat(response.statusCode()).isEqualTo(403);
    assertThat(methodsOf(intruder)).containsExactly("PASSWORD");
    assertThat(redis.hasKey("oauth:link:" + token)).isTrue();
    // ...so the owner can still finish what they started.
    assertThat(confirmLink(owner, linkCookie).statusCode()).isEqualTo(200);
  }

  @Test
  void confirmingWithoutALinkCookieIs404() throws Exception {
    Account user = registerCredentialAccount();

    HttpResponse<String> response =
        send("POST", "/api/users/me/auth-methods/google", user.cookie(), null);

    assertThat(response.statusCode()).isEqualTo(404);
  }

  @Test
  void aSubAlreadyHeldByAnotherAccountIs409OnConfirmAndOnTheCallback() throws Exception {
    String heldSub = "google-sub-" + UUID.randomUUID();
    Account holder = registerCredentialAccount();
    assertThat(confirmLink(holder, linkCookieFor(holder, heldSub, holder.email())).statusCode())
        .isEqualTo(200);

    // Callback side: the already-held sub is caught before any record is written.
    Account other = registerCredentialAccount();
    String state = linkState(other);
    googleIdentity(heldSub, other.email());
    HttpResponse<String> callback = send("GET", callbackPath(state), null, null);
    assertThat(location(callback)).isEqualTo("/settings?linkError=1");
    assertThat(callback.headers().allValues(HttpHeaders.SET_COOKIE))
        .noneMatch(c -> c.startsWith("turnbase_oauth_link="));

    // Confirm side: a record that predates the other account's link still loses the race.
    String staleToken = UUID.randomUUID().toString();
    redis.opsForValue().set("oauth:link:" + staleToken, recordJson(other.userId(), heldSub));
    HttpResponse<String> confirm = confirmLink(other, "turnbase_oauth_link=" + staleToken);

    assertThat(confirm.statusCode()).isEqualTo(409);
    assertThat(methodsOf(other)).containsExactly("PASSWORD");
    assertThat(emailOf(other)).isEqualTo(other.email());
    assertThat(emailSourceOf(other)).isEqualTo("SELF");
  }

  @Test
  void linkingGoogleWhenGoogleIsAlreadyLinkedIs409() throws Exception {
    Account user = registerGoogleAccount();
    String linkCookie = linkCookieFor(user, "google-sub-" + UUID.randomUUID(), user.email());

    HttpResponse<String> response = confirmLink(user, linkCookie);

    assertThat(response.statusCode()).isEqualTo(409);
    assertThat(methodsOf(user)).containsExactly("GOOGLE");
  }

  // ---- account fixtures ----

  private Account registerCredentialAccount() throws Exception {
    String username = uniqueUsername();
    String email = "user-" + UUID.randomUUID() + "@example.com";
    HttpResponse<String> response =
        send(
            "POST",
            "/api/auth/register",
            null,
            """
            {"username":"%s","email":"%s","password":"%s","confirmPassword":"%s"}
            """
                .formatted(username, email, PASSWORD, PASSWORD));
    assertThat(response.statusCode()).isEqualTo(201);
    return new Account(
        UUID.fromString((String) parse(response).get("userId")),
        username,
        email,
        sessionCookie(response));
  }

  /** A first-time Google login, completed: GOOGLE is the only method and email_source is OAUTH. */
  private Account registerGoogleAccount() throws Exception {
    String username = uniqueUsername();
    String email = "user-" + UUID.randomUUID() + "@example.com";
    googleIdentity("google-sub-" + UUID.randomUUID(), email);
    String state = stateFrom(send("GET", "/auth/google", null, null));
    HttpResponse<String> callback = send("GET", callbackPath(state), null, null);
    String pending = cookieNamed(callback, "turnbase_oauth_pending").split(";", 2)[0];
    HttpResponse<String> complete =
        send(
            "POST",
            "/api/auth/oauth/complete-registration",
            pending,
            "{\"username\":\"%s\"}".formatted(username));
    assertThat(complete.statusCode()).isEqualTo(201);
    return new Account(
        UUID.fromString((String) parse(complete).get("userId")),
        username,
        email,
        sessionCookie(complete));
  }

  private void linkGoogle(Account user, String sub) throws Exception {
    String linkCookie = linkCookieFor(user, sub, user.email());
    assertThat(confirmLink(user, linkCookie).statusCode()).isEqualTo(200);
  }

  /** Steps 1 and 2 of §7a; returns the {@code name=value} pair the confirming request sends. */
  private String linkCookieFor(Account user, String sub, String googleEmail) throws Exception {
    String state = linkState(user);
    googleIdentity(sub, googleEmail);
    HttpResponse<String> callback = send("GET", callbackPath(state), null, null);
    return cookieNamed(callback, "turnbase_oauth_link").split(";", 2)[0];
  }

  private String linkState(Account user) throws Exception {
    HttpResponse<String> initiate = send("GET", "/auth/google?intent=link", user.cookie(), null);
    assertThat(initiate.statusCode()).isEqualTo(302);
    return stateFrom(initiate);
  }

  private HttpResponse<String> confirmLink(Account user, String linkCookie) throws Exception {
    return send(
        "POST", "/api/users/me/auth-methods/google", user.cookie() + "; " + linkCookie, null);
  }

  private HttpResponse<String> addPassword(Account user, String password, String confirm)
      throws Exception {
    return send(
        "POST",
        "/api/users/me/auth-methods/password",
        user.cookie(),
        "{\"password\":\"%s\",\"confirmPassword\":\"%s\"}".formatted(password, confirm));
  }

  private HttpResponse<String> removeMethod(Account user, String method) throws Exception {
    return send("DELETE", "/api/users/me/auth-methods/" + method, user.cookie(), null);
  }

  private void googleIdentity(String sub, String email) {
    userInfoResponse =
        "{\"sub\":\"%s\",\"email\":\"%s\",\"email_verified\":true}".formatted(sub, email);
  }

  // ---- database reads ----

  private List<String> methodsOf(Account user) {
    return jdbcClient
        .sql("select method from user_auth_method where user_id = :id")
        .param("id", user.userId())
        .query(String.class)
        .list();
  }

  private long methodCount(Account user) {
    return methodsOf(user).size();
  }

  private String emailSourceOf(Account user) {
    return jdbcClient
        .sql("select email_source from app_user where user_id = :id")
        .param("id", user.userId())
        .query(String.class)
        .single();
  }

  private String emailOf(Account user) {
    return jdbcClient
        .sql("select email from app_user where user_id = :id")
        .param("id", user.userId())
        .query(String.class)
        .single();
  }

  // ---- HTTP helpers ----

  private HttpResponse<String> send(
      String method, String path, @Nullable String cookie, @Nullable String body) throws Exception {
    HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path));
    if (body != null) {
      builder.header("Content-Type", "application/json");
    }
    if (cookie != null) {
      builder.header("Cookie", cookie);
    }
    builder.method(
        method,
        body == null
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(body));
    return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

  private static String callbackPath(String state) {
    return "/auth/callback?code=auth-code&state=" + state;
  }

  private static String location(HttpResponse<String> response) {
    return response.headers().firstValue("Location").orElseThrow();
  }

  private static String stateFrom(HttpResponse<String> initiate) {
    String query = URI.create(location(initiate)).getRawQuery();
    for (String param : query.split("&")) {
      String[] parts = param.split("=", 2);
      if ("state".equals(parts[0])) {
        return parts[1];
      }
    }
    throw new IllegalStateException("no state parameter in " + location(initiate));
  }

  private static String cookieNamed(HttpResponse<String> response, String name) {
    return response.headers().allValues(HttpHeaders.SET_COOKIE).stream()
        .filter(c -> c.startsWith(name + "="))
        .findFirst()
        .orElseThrow();
  }

  private static String sessionCookie(HttpResponse<String> response) {
    return cookieNamed(response, "turnbase_session").split(";", 2)[0];
  }

  private String recordJson(UUID userId, String sub) throws Exception {
    return objectMapper.writeValueAsString(
        Map.of("userId", userId.toString(), "providerSubject", sub));
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> parse(HttpResponse<String> response) throws Exception {
    return objectMapper.readValue(response.body(), Map.class);
  }

  private static String uniqueUsername() {
    return "user" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
  }

  private static void respond(com.sun.net.httpserver.HttpExchange exchange, String body)
      throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(200, bytes.length);
    try (OutputStream os = exchange.getResponseBody()) {
      os.write(bytes);
    }
  }

  private record Account(UUID userId, String username, String email, String cookie) {}
}
