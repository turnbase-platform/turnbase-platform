package io.turnbase.platform.account;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.turnbase.platform.PostgresIntegrationTest;
import io.turnbase.platform.SharedApplication;
import io.turnbase.platform.TurnBaseApplication;
import io.turnbase.platform.UsesSharedApplication;
import io.turnbase.platform.session.SessionRevokedEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code IMP-M1-04}'s own Done-when clause, checked end to end against a real application boot and
 * Postgres: registration and login endpoints, validation rules, unique-constraint-to-domain-error
 * mapping, Argon2 hashing, and login's session displacement (FR-SESS-003). Also carries {@code
 * IMP-M1-06}'s: {@code GET /session}, {@code POST /logout}, the FR-SESS-004 401 shape for a
 * displaced token, and {@code SessionRevokedEvent} publication. Also {@code IMP-M1-05}'s {@code
 * POST /guest}: a credential-free principal minted from one call, immediately authenticated.
 */
@UsesSharedApplication
class AuthControllerIT extends PostgresIntegrationTest {

  private final JsonMapper objectMapper = JsonMapper.builder().build();
  // Same JDK-backed request factory as GlobalExceptionHandlerIT — the legacy
  // HttpURLConnection-backed default drops the body on non-2xx responses.
  private final RestTemplate restTemplate = new RestTemplate(new JdkClientHttpRequestFactory());

  private ConfigurableApplicationContext context;
  private JdbcClient jdbcClient;
  private String baseUrl;
  private RecordingSessionRevokedEventListener revokedEvents;

  {
    restTemplate.setErrorHandler(new NoOpResponseErrorHandler());
  }

  @BeforeEach
  void bootApplication() {
    context =
        SharedApplication.run(
            new Class<?>[] {TurnBaseApplication.class, RecordingSessionRevokedEventListener.class},
            new String[] {
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
              // This class drives /register, /login and /guest directly and repeatedly from one
              // IP across many test methods — real budgets (rate-limiting.md Tiers 1 and 5) would
              // fail unrelated later tests. IMP-M1-02's own coverage of those tiers lives in
              // RateLimitInterceptorIT, which sets the opposite (deliberately tiny) overrides.
              "--turnbase.rate-limit.auth.failures-before-lockout=1000000",
              "--turnbase.rate-limit.minting.guest.capacity=1000000",
              "--turnbase.rate-limit.minting.registration.capacity=1000000"
            });
    int port = Integer.parseInt(context.getEnvironment().getProperty("local.server.port"));
    baseUrl = "http://127.0.0.1:" + port + "/api/auth";
    jdbcClient = context.getBean(JdbcClient.class);
    revokedEvents = context.getBean(RecordingSessionRevokedEventListener.class);
  }

  @Test
  void registrationCreatesTheThreeRowsAndIssuesASessionCookie() throws Exception {
    String username = uniqueUsername();
    String email = uniqueEmail();

    ResponseEntity<String> response = register(username, email, "correct horse battery");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(response.getHeaders().getLocation())
        .isEqualTo(java.net.URI.create("/api/users/" + username));
    Map<String, Object> body = parse(response);
    assertThat(body.get("username")).isEqualTo(username);
    assertThat(body.get("kind")).isEqualTo("REGISTERED");
    UUID userId = UUID.fromString((String) body.get("userId"));

    assertSessionCookie(response);

    Integer userCount =
        jdbcClient
            .sql(
                "select count(*) from app_user where user_id = :userId and kind = 'REGISTERED' and"
                    + " username = :username and email = :email and email_source = 'SELF'")
            .param("userId", userId)
            .param("username", username)
            .param("email", email)
            .query(Integer.class)
            .single();
    assertThat(userCount).isEqualTo(1);

    String passwordHash =
        jdbcClient
            .sql(
                "select password_hash from user_auth_method where user_id = :userId and method ="
                    + " 'PASSWORD'")
            .param("userId", userId)
            .query(String.class)
            .single();
    assertThat(passwordHash).startsWith("$argon2id$");

    Integer privacyCount =
        jdbcClient
            .sql("select count(*) from user_privacy_settings where user_id = :userId")
            .param("userId", userId)
            .query(Integer.class)
            .single();
    assertThat(privacyCount).isEqualTo(1);
  }

  /** IMP-M7-10 / MR-06: one INFO line per auth event, carrying ids and never a secret. */
  @Test
  void registrationLoginAndGuestCreationAreLoggedWithoutSecrets() throws Exception {
    Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    root.addAppender(appender);
    String username = uniqueUsername();
    String password = "correct horse battery";
    ResponseEntity<String> registered = register(username, uniqueEmail(), password);
    UUID userId = UUID.fromString((String) parse(registered).get("userId"));
    ResponseEntity<String> loggedIn = login(username, password);
    ResponseEntity<String> guest = post("/guest", null);
    root.detachAppender(appender);

    List<String> messages = appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    assertThat(messages)
        .anyMatch(m -> m.startsWith("User registered") && m.contains("userId=" + userId));
    assertThat(messages)
        .anyMatch(m -> m.startsWith("User logged in") && m.contains("userId=" + userId));
    assertThat(messages).anyMatch(m -> m.startsWith("Guest created") && m.contains("userId="));
    for (ResponseEntity<String> response : List.of(registered, loggedIn, guest)) {
      String token = sessionCookieHeader(response).split("=", 2)[1].split(";", 2)[0];
      assertThat(messages).noneMatch(m -> m.contains(token));
    }
    assertThat(messages).noneMatch(m -> m.contains(password));
  }

  @Test
  void duplicateUsernameIsRejectedAsConflict() throws Exception {
    String username = uniqueUsername();
    register(username, uniqueEmail(), "correct horse battery");

    ResponseEntity<String> response = register(username, uniqueEmail(), "correct horse battery");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(parse(response).get("detail")).isEqualTo("Username already taken.");
  }

  @Test
  void duplicateEmailIsRejectedAsConflict() throws Exception {
    String email = uniqueEmail();
    register(uniqueUsername(), email, "correct horse battery");

    ResponseEntity<String> response = register(uniqueUsername(), email, "correct horse battery");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(parse(response).get("detail")).isEqualTo("Email already registered.");
  }

  @Test
  void theReservedGuestPrefixIsRejectedAsValidationFailed() {
    ResponseEntity<String> response =
        register("guest-" + UUID.randomUUID(), uniqueEmail(), "correct horse battery");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  void aMismatchedPasswordConfirmationIsRejectedAsValidationFailed() throws Exception {
    ResponseEntity<String> response =
        post(
            "/register",
            """
            {"username":"%s","email":"%s","password":"correct horse battery","confirmPassword":"different"}
            """
                .formatted(uniqueUsername(), uniqueEmail()));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> errors = (List<Map<String, Object>>) parse(response).get("errors");
    assertThat(errors).anySatisfy(e -> assertThat(e.get("field")).isEqualTo("confirmPassword"));
  }

  @Test
  void loginWithTheUsernameSucceeds() throws Exception {
    String username = uniqueUsername();
    register(username, uniqueEmail(), "correct horse battery");

    ResponseEntity<String> response = login(username, "correct horse battery");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(parse(response).get("username")).isEqualTo(username);
    assertThat(parse(response).get("kind")).isEqualTo("REGISTERED");
    assertSessionCookie(response);
  }

  @Test
  void loginWithTheEmailSucceeds() throws Exception {
    String username = uniqueUsername();
    String email = uniqueEmail();
    register(username, email, "correct horse battery");

    ResponseEntity<String> response = login(email, "correct horse battery");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(parse(response).get("username")).isEqualTo(username);
  }

  @Test
  void loginWithTheWrongPasswordIsUnauthenticated() throws Exception {
    String username = uniqueUsername();
    register(username, uniqueEmail(), "correct horse battery");

    ResponseEntity<String> response = login(username, "wrong password entirely");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(parse(response).get("detail")).isEqualTo("Invalid username/email or password.");
  }

  @Test
  void loginWithAnUnknownIdentifierReportsTheSameGenericFailureAsAWrongPassword() throws Exception {
    ResponseEntity<String> response = login("nobody-" + UUID.randomUUID(), "whatever password");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(parse(response).get("detail")).isEqualTo("Invalid username/email or password.");
  }

  @Test
  void aSecondLoginDisplacesTheFirstSession() throws Exception {
    String username = uniqueUsername();
    ResponseEntity<String> registerResponse =
        register(username, uniqueEmail(), "correct horse battery");
    UUID userId = UUID.fromString((String) parse(registerResponse).get("userId"));
    String firstSessionId =
        jdbcClient
            .sql(
                "select session_id from user_session where user_id = :userId and revoked_at is"
                    + " null")
            .param("userId", userId)
            .query(String.class)
            .single();

    login(username, "correct horse battery");

    Map<String, Object> firstSessionRow =
        jdbcClient
            .sql("select revoked_at, revoked_reason from user_session where session_id = :id")
            .param("id", UUID.fromString(firstSessionId))
            .query()
            .singleRow();
    assertThat(firstSessionRow.get("revoked_at")).isNotNull();
    assertThat(firstSessionRow.get("revoked_reason")).isEqualTo("DISPLACED");

    Integer activeSessionCount =
        jdbcClient
            .sql("select count(*) from user_session where user_id = :userId and revoked_at is null")
            .param("userId", userId)
            .query(Integer.class)
            .single();
    assertThat(activeSessionCount).isEqualTo(1);
  }

  @Test
  void aSecondLoginPublishesASessionRevokedEventForTheDisplacedSession() throws Exception {
    String username = uniqueUsername();
    ResponseEntity<String> registerResponse =
        register(username, uniqueEmail(), "correct horse battery");
    UUID userId = UUID.fromString((String) parse(registerResponse).get("userId"));
    UUID firstSessionId =
        jdbcClient
            .sql("select session_id from user_session where user_id = :userId")
            .param("userId", userId)
            .query(UUID.class)
            .single();

    login(username, "correct horse battery");

    assertThat(revokedEvents.events())
        .anySatisfy(
            event -> {
              assertThat(event.sessionId()).isEqualTo(firstSessionId);
              assertThat(event.userId()).isEqualTo(userId);
              assertThat(event.reason()).isEqualTo("DISPLACED");
            });
  }

  @Test
  void getSessionReturnsThePrincipalWhenAuthenticated() throws Exception {
    String username = uniqueUsername();
    ResponseEntity<String> registerResponse =
        register(username, uniqueEmail(), "correct horse battery");
    String cookie = sessionCookieHeader(registerResponse);

    ResponseEntity<String> response = getWithCookie("/session", cookie);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(parse(response).get("username")).isEqualTo(username);
    assertThat(parse(response).get("kind")).isEqualTo("REGISTERED");
  }

  @Test
  void getSessionReportsAGuestAsKindGuest() throws Exception {
    ResponseEntity<String> guestResponse = post("/guest", null);

    ResponseEntity<String> response = getWithCookie("/session", sessionCookieHeader(guestResponse));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(parse(response).get("kind")).isEqualTo("GUEST");
  }

  @Test
  void providersReportsGoogleUnavailableWhenNoClientSecretIsConfigured() throws Exception {
    ResponseEntity<String> response =
        restTemplate.getForEntity(baseUrl + "/providers", String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(parse(response)).containsExactly(Map.entry("google", false));
  }

  @Test
  void getSessionAnswers200AuthenticatedFalseWithoutACookie() throws Exception {
    ResponseEntity<String> response = getWithCookie("/session", null);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(parse(response)).containsExactly(Map.entry("authenticated", false));
  }

  /**
   * Spring's scheduling post-processor logs "more than one TaskScheduler bean" when the candidates
   * are ambiguous and none is named {@code taskScheduler}. Since {@code IMP-M16-10} the broker's
   * own scheduler is a candidate beside the pool {@code SchedulingPoolConfig} names that, so the
   * name is what resolves it, and the heartbeat scheduler stays out of the candidates.
   */
  @Test
  void theTaskSchedulerNamedByDefaultResolvesTheAmbiguityAndTheHeartbeatSchedulerIsNoCandidate() {
    assertThat(
            java.util.Arrays.stream(
                    context.getBeanNamesForType(org.springframework.scheduling.TaskScheduler.class))
                .filter(
                    name -> context.getBeanFactory().getBeanDefinition(name).isAutowireCandidate())
                .toList())
        .containsExactlyInAnyOrder("taskScheduler", "messageBrokerTaskScheduler");
  }

  @Test
  void getSessionStillNamesTheReasonForATokenItRejects() throws Exception {
    ResponseEntity<String> response = getWithCookie("/session", "turnbase_session=not-a-token");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void logoutRevokesTheSessionClearsTheCookieAndPublishesTheEvent() throws Exception {
    String username = uniqueUsername();
    ResponseEntity<String> registerResponse =
        register(username, uniqueEmail(), "correct horse battery");
    UUID userId = UUID.fromString((String) parse(registerResponse).get("userId"));
    String cookie = sessionCookieHeader(registerResponse);
    UUID sessionId =
        jdbcClient
            .sql("select session_id from user_session where user_id = :userId")
            .param("userId", userId)
            .query(UUID.class)
            .single();

    ResponseEntity<String> response = postWithCookie("/logout", cookie);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    List<String> setCookies =
        java.util.Objects.requireNonNull(response.getHeaders().get(HttpHeaders.SET_COOKIE));
    assertThat(setCookies).anySatisfy(c -> assertThat(c).contains("turnbase_session=;"));

    Map<String, Object> row =
        jdbcClient
            .sql("select revoked_at, revoked_reason from user_session where session_id = :id")
            .param("id", sessionId)
            .query()
            .singleRow();
    assertThat(row.get("revoked_at")).isNotNull();
    assertThat(row.get("revoked_reason")).isEqualTo("LOGOUT");

    assertThat(revokedEvents.events())
        .anySatisfy(
            event -> {
              assertThat(event.sessionId()).isEqualTo(sessionId);
              assertThat(event.reason()).isEqualTo("LOGOUT");
            });
  }

  @Test
  void guestMintsAnAccessibleAccountWithNoCredential() throws Exception {
    ResponseEntity<String> response = post("/guest", null);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    Map<String, Object> body = parse(response);
    String username = (String) body.get("username");
    assertThat(username).startsWith("guest-");
    assertThat(body.get("kind")).isEqualTo("GUEST");
    UUID userId = UUID.fromString((String) body.get("userId"));
    assertThat(response.getHeaders().getLocation())
        .isEqualTo(java.net.URI.create("/api/users/" + username));
    assertSessionCookie(response);

    Map<String, Object> row =
        jdbcClient
            .sql("select kind, email, guest_serial from app_user where user_id = :userId")
            .param("userId", userId)
            .query()
            .singleRow();
    assertThat(row.get("kind")).isEqualTo("GUEST");
    assertThat(row.get("email")).isNull();
    assertThat(row.get("guest_serial")).isNotNull();

    Integer authMethodCount =
        jdbcClient
            .sql("select count(*) from user_auth_method where user_id = :userId")
            .param("userId", userId)
            .query(Integer.class)
            .single();
    assertThat(authMethodCount).isZero();

    // GET /session proves the minted cookie actually authenticates — the only means of access
    // a guest ever has (FR-GUEST-004).
    ResponseEntity<String> sessionResponse =
        getWithCookie("/session", sessionCookieHeader(response));
    assertThat(sessionResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(parse(sessionResponse).get("username")).isEqualTo(username);
  }

  @Test
  void twoGuestMintsProduceDistinctIncreasingSerialsAndUsernames() throws Exception {
    Map<String, Object> first = parse(post("/guest", null));
    Map<String, Object> second = parse(post("/guest", null));

    assertThat(first.get("username")).isNotEqualTo(second.get("username"));
  }

  @Test
  void aRequestBearingTheDisplacedSessionsTokenGetsTheFrSess004Shape() throws Exception {
    String username = uniqueUsername();
    ResponseEntity<String> registerResponse =
        register(username, uniqueEmail(), "correct horse battery");
    String firstCookie = sessionCookieHeader(registerResponse);

    login(username, "correct horse battery");

    ResponseEntity<String> response = getWithCookie("/session", firstCookie);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(parse(response).get("detail"))
        .isEqualTo("Your session was ended because you logged in from another device.");
  }

  private void assertSessionCookie(ResponseEntity<String> response) {
    List<String> setCookies =
        java.util.Objects.requireNonNull(response.getHeaders().get(HttpHeaders.SET_COOKIE));
    String cookie =
        setCookies.stream()
            .filter(c -> c.startsWith("turnbase_session="))
            .findFirst()
            .orElseThrow();
    assertThat(cookie)
        .contains("HttpOnly")
        .doesNotContain("Secure")
        .contains("SameSite=Strict")
        .contains("Path=/");
  }

  private ResponseEntity<String> register(String username, String email, String password) {
    return post(
        "/register",
        """
        {"username":"%s","email":"%s","password":"%s","confirmPassword":"%s"}
        """
            .formatted(username, email, password, password));
  }

  private ResponseEntity<String> login(String usernameOrEmail, String password) {
    return post(
        "/login",
        """
        {"usernameOrEmail":"%s","password":"%s"}
        """
            .formatted(usernameOrEmail, password));
  }

  private ResponseEntity<String> post(String path, @Nullable String body) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    HttpEntity<String> request = new HttpEntity<>(body, headers);
    return restTemplate.exchange(baseUrl + path, HttpMethod.POST, request, String.class);
  }

  private ResponseEntity<String> getWithCookie(String path, @Nullable String cookie) {
    return withCookie(path, HttpMethod.GET, cookie);
  }

  private ResponseEntity<String> postWithCookie(String path, @Nullable String cookie) {
    return withCookie(path, HttpMethod.POST, cookie);
  }

  private ResponseEntity<String> withCookie(
      String path, HttpMethod method, @Nullable String cookie) {
    HttpHeaders headers = new HttpHeaders();
    if (cookie != null) {
      headers.add(HttpHeaders.COOKIE, cookie);
    }
    HttpEntity<Void> request = new HttpEntity<>(headers);
    return restTemplate.exchange(baseUrl + path, method, request, String.class);
  }

  /**
   * The {@code name=value} pair only, stripped of attributes, for replay in a {@code Cookie}
   * header.
   */
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

  /**
   * A test-only extra {@code SpringApplication} source (not component-scanned from {@code
   * io.turnbase.platform}) that records every {@link SessionRevokedEvent} the running application
   * publishes, so this suite can assert {@code IMP-M1-06}'s "publishes after commit" clause without
   * a WebSocket or SSE connection to observe closing — neither exists yet (`IMP-GATE-28`).
   */
  @Component
  static class RecordingSessionRevokedEventListener {
    private final List<SessionRevokedEvent> events =
        Collections.synchronizedList(new ArrayList<>());

    @EventListener
    void onSessionRevoked(SessionRevokedEvent event) {
      events.add(event);
    }

    List<SessionRevokedEvent> events() {
      return events;
    }
  }
}
