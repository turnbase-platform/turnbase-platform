package io.turnbase.platform.account;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.turnbase.platform.PostgresIntegrationTest;
import io.turnbase.platform.TurnBaseApplication;
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
 * {@code IMP-M1-04}'s own Done-when clause, checked end to end against a real application boot and
 * Postgres: registration and login endpoints, validation rules, unique-constraint-to-domain-error
 * mapping, Argon2 hashing, and login's session displacement (FR-SESS-003).
 */
class AuthControllerIT extends PostgresIntegrationTest {

  private final ObjectMapper objectMapper = new ObjectMapper();
  // Same JDK-backed request factory as GlobalExceptionHandlerIT — the legacy
  // HttpURLConnection-backed default drops the body on non-2xx responses.
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
    baseUrl = "http://127.0.0.1:" + port + "/api/auth";
    jdbcClient = context.getBean(JdbcClient.class);
  }

  @AfterEach
  void stopApplication() {
    context.close();
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
        .contains("Secure")
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
    public void handleError(org.springframework.http.client.ClientHttpResponse response) {
      // Never called: hasError() always returns false so assertions see the real status.
    }
  }
}
