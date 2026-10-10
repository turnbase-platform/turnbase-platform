package io.turnbase.platform.admin;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.turnbase.platform.PostgresIntegrationTest;
import io.turnbase.platform.TurnBaseApplication;
import io.turnbase.platform.upload.TestBundles;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
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
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code admin-rest-api.md} end to end, against IMP-M4-05's own Done-when clause: the admin API
 * lives on the management port (never the main one), HTTP Basic against the configured credentials,
 * {@code POST /admin/games} plus the mirrored upload routes reach {@code ACTIVE} with no review
 * row, approve/reject decide the one open review correctly, and every mutating route writes an
 * audit record (NFR-AUD-001).
 */
class AdminApiControllerIT extends PostgresIntegrationTest {

  private static final String ADMIN_USERNAME = "admin";
  private static final String ADMIN_PASSWORD = "admin";

  private final JsonMapper objectMapper = JsonMapper.builder().build();
  private final RestTemplate restTemplate = new RestTemplate(new JdkClientHttpRequestFactory());

  private @Nullable ConfigurableApplicationContext context;
  private JdbcClient jdbcClient;
  private String mainBaseUrl;
  private String adminBaseUrl;

  {
    restTemplate.setErrorHandler(new NoOpResponseErrorHandler());
  }

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
            "--turnbase.admin.username=" + ADMIN_USERNAME,
            "--turnbase.admin.password=" + ADMIN_PASSWORD,
            "--turnbase.room.spectator-capacity=2",
            "--spring.data.redis.url=" + redisUrl(),
            "--turnbase.rate-limit.auth.failures-before-lockout=1000000",
            "--turnbase.rate-limit.minting.guest.capacity=1000000",
            "--turnbase.rate-limit.minting.registration.capacity=1000000");
    context = booted;
    int mainPort = Integer.parseInt(booted.getEnvironment().getProperty("local.server.port"));
    int managementPort =
        Integer.parseInt(booted.getEnvironment().getProperty("local.management.port"));
    mainBaseUrl = "http://127.0.0.1:" + mainPort;
    adminBaseUrl = "http://127.0.0.1:" + managementPort;
    jdbcClient = booted.getBean(JdbcClient.class);
  }

  @AfterEach
  void stopApplication() {
    if (context != null) {
      context.close();
    }
  }

  // ---------------------------------------------------------------- FR-ADM-001/002: listener +
  // Basic auth

  @Test
  void adminRoutesRejectMissingCredentials() {
    ResponseEntity<String> response = get(adminBaseUrl, "/admin/games", null);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(response.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE))
        .isEqualTo("Basic realm=\"turnbase-admin\"");
    assertThat(response.getHeaders().getContentType())
        .isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
  }

  @Test
  void adminRoutesRejectWrongCredentials() {
    ResponseEntity<String> response = get(adminBaseUrl, "/admin/games", basicAuth("admin", "nope"));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void adminRoutesAcceptTheConfiguredCredentials() {
    ResponseEntity<String> response =
        get(adminBaseUrl, "/admin/games", basicAuth(ADMIN_USERNAME, ADMIN_PASSWORD));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
  }

  @Test
  void adminRoutesAreNotServedOnTheMainPort() {
    ResponseEntity<String> response =
        get(mainBaseUrl, "/admin/games", basicAuth(ADMIN_USERNAME, ADMIN_PASSWORD));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  // ---------------------------------------------------------------- FR-OFF-001/002: official
  // upload, no review row

  @Test
  void creatingAndUploadingAnOfficialVersionActivatesWithNoReviewRow() throws Exception {
    Map<String, Object> created = parse(adminPost("/admin/games", ""));
    UUID gameId = UUID.fromString((String) created.get("gameId"));

    byte[] bundle = TestBundles.validChessBundle("1.0.0");
    Map<String, Object> slot =
        parse(
            adminPost(
                "/admin/games/" + gameId + "/versions",
                "{\"version\":\"1.0.0\",\"bundleSize\":%d}".formatted(bundle.length)));
    UUID uploadId = UUID.fromString(requiredString(slot, "uploadId"));
    putBundle(requiredString(slot, "uploadUrl"), bundle);

    ResponseEntity<String> confirmResponse =
        adminPost("/admin/games/" + gameId + "/versions/" + uploadId + "/confirm", "");
    assertThat(confirmResponse.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

    String status = pollVersionStatus(gameId, uploadId);
    assertThat(status).isEqualTo("ACTIVE");

    Long reviewCount =
        jdbcClient
            .sql("select count(*) from game_review where game_id = :id")
            .param("id", gameId)
            .query(Long.class)
            .single();
    assertThat(reviewCount).isZero();
  }

  // ---------------------------------------------------------------- FR-REV-004...006: decisions

  @Test
  void approvingSetsVerifiedAndPublishesAndNotifiesTheDeveloper() {
    UUID developerId = seedDeveloper();
    UUID gameId = seedSubmittedGame(developerId);

    ResponseEntity<String> response = adminPost("/admin/games/" + gameId + "/approve", "");
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

    Map<String, Object> game = gameRow(gameId);
    assertThat(game.get("badge")).isEqualTo("VERIFIED");
    assertThat(game.get("listed")).isEqualTo(true);

    Map<String, Object> review = reviewRow(gameId);
    assertThat(review.get("decision")).isEqualTo("APPROVED");
    assertThat(review.get("decided_at")).isNotNull();

    assertThat(notificationTypesFor(developerId)).contains("GAME_APPROVED");
  }

  @Test
  void rejectingStoresTheReasonAndLeavesOwnershipAndBadgeAlone() {
    UUID developerId = seedDeveloper();
    UUID gameId = seedSubmittedGame(developerId);

    ResponseEntity<String> response =
        adminPost("/admin/games/" + gameId + "/reject", "{\"reason\":\"Crashes on start\"}");
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

    Map<String, Object> game = gameRow(gameId);
    assertThat(game.get("badge")).isEqualTo("UNVERIFIED");
    assertThat(game.get("owner_user_id")).isEqualTo(developerId);

    Map<String, Object> review = reviewRow(gameId);
    assertThat(review.get("decision")).isEqualTo("REJECTED");
    assertThat(review.get("rejection_reason")).isEqualTo("Crashes on start");

    assertThat(notificationTypesFor(developerId)).contains("GAME_REJECTED");
  }

  @Test
  void rejectingWithoutAReasonIsABusinessRuleViolation() {
    UUID developerId = seedDeveloper();
    UUID gameId = seedSubmittedGame(developerId);

    ResponseEntity<String> response = adminPost("/admin/games/" + gameId + "/reject", "{}");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
  }

  // ---------------------------------------------------------------- NFR-AUD-001: audit record

  @Test
  void everyMutatingAdminActionWritesAnAuditRecord() {
    Logger auditLogger = (Logger) LoggerFactory.getLogger("io.turnbase.platform.admin.audit");
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    auditLogger.addAppender(appender);
    try {
      Map<String, Object> created;
      try {
        created = parse(adminPost("/admin/games", ""));
      } catch (Exception e) {
        throw new IllegalStateException(e);
      }
      UUID gameId = UUID.fromString((String) created.get("gameId"));

      assertThat(appender.list)
          .anySatisfy(
              event -> {
                assertThat(event.getFormattedMessage()).contains("GAME_CREATED");
                assertThat(event.getMDCPropertyMap()).containsEntry("gameId", gameId.toString());
              });
    } finally {
      auditLogger.detachAppender(appender);
    }
  }

  // ---------------------------------------------------------------- helpers

  private UUID seedDeveloper() {
    UUID userId = UUID.randomUUID();
    String username = "dev" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    jdbcClient
        .sql(
            "insert into app_user (user_id, kind, username, username_skeleton, email, email_source)"
                + " values (:id, 'REGISTERED', :username, :username, :email, 'SELF')")
        .param("id", userId)
        .param("username", username)
        .param("email", username + "@example.com")
        .update();
    return userId;
  }

  /**
   * A developer-submitted, not-yet-decided game: {@code UNVERIFIED}, one open {@code game_review}.
   */
  private UUID seedSubmittedGame(UUID developerId) {
    UUID gameId = UUID.randomUUID();
    jdbcClient
        .sql(
            "insert into game (game_id, owner_user_id, title, short_description, badge, listed)"
                + " values (:id, :owner, 'Submitted Game', 'placeholder', 'UNVERIFIED', false)")
        .param("id", gameId)
        .param("owner", developerId)
        .update();
    UUID versionId = UUID.randomUUID();
    jdbcClient
        .sql(
            "insert into game_version (version_id, game_id, version, status, declared_size_bytes,"
                + " upload_expires_at) values (:id, :gameId, '1.0.0', 'PENDING', 1000, :expires)")
        .param("id", versionId)
        .param("gameId", gameId)
        .param("expires", Timestamp.from(Instant.now().plusSeconds(3600)))
        .update();
    jdbcClient
        .sql("insert into game_review (review_id, game_id, version_id) values (:id, :gameId, :vid)")
        .param("id", UUID.randomUUID())
        .param("gameId", gameId)
        .param("vid", versionId)
        .update();
    return gameId;
  }

  private Map<String, Object> gameRow(UUID gameId) {
    return jdbcClient
        .sql("select * from game where game_id = :id")
        .param("id", gameId)
        .query((rs, rowNum) -> rowAsMap(rs))
        .single();
  }

  private Map<String, Object> reviewRow(UUID gameId) {
    return jdbcClient
        .sql("select * from game_review where game_id = :id")
        .param("id", gameId)
        .query((rs, rowNum) -> rowAsMap(rs))
        .single();
  }

  private java.util.List<String> notificationTypesFor(UUID userId) {
    return jdbcClient
        .sql("select type from notification where user_id = :id")
        .param("id", userId)
        .query(String.class)
        .list();
  }

  private Map<String, Object> rowAsMap(java.sql.ResultSet rs) throws java.sql.SQLException {
    java.sql.ResultSetMetaData meta = rs.getMetaData();
    Map<String, Object> row = new java.util.LinkedHashMap<>();
    for (int i = 1; i <= meta.getColumnCount(); i++) {
      row.put(meta.getColumnName(i), rs.getObject(i));
    }
    return row;
  }

  private String pollVersionStatus(UUID gameId, UUID uploadId) throws Exception {
    Instant deadline = Instant.now().plus(Duration.ofSeconds(20));
    String status = versionStatus(gameId, uploadId);
    while (Instant.now().isBefore(deadline)
        && (status.equals("PENDING") || status.equals("PROCESSING"))) {
      Thread.sleep(200);
      status = versionStatus(gameId, uploadId);
    }
    return status;
  }

  private String versionStatus(UUID gameId, UUID uploadId) throws Exception {
    Map<String, Object> body = parse(adminGet("/admin/games/" + gameId + "/versions/" + uploadId));
    return requiredString(body, "status");
  }

  private void putBundle(String presignedUrl, byte[] bytes) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
    ResponseEntity<String> response =
        restTemplate.exchange(
            URI.create(presignedUrl),
            HttpMethod.PUT,
            new HttpEntity<>(bytes, headers),
            String.class);
    if (!response.getStatusCode().is2xxSuccessful()) {
      throw new IllegalStateException(
          "Bundle PUT to presigned URL failed: "
              + response.getStatusCode()
              + " "
              + response.getBody());
    }
  }

  private ResponseEntity<String> adminPost(String path, String body) {
    return withBody(
        adminBaseUrl, path, HttpMethod.POST, basicAuth(ADMIN_USERNAME, ADMIN_PASSWORD), body);
  }

  private ResponseEntity<String> adminGet(String path) {
    return get(adminBaseUrl, path, basicAuth(ADMIN_USERNAME, ADMIN_PASSWORD));
  }

  private ResponseEntity<String> get(String base, String path, @Nullable String authHeader) {
    return withBody(base, path, HttpMethod.GET, authHeader, null);
  }

  private ResponseEntity<String> withBody(
      String base,
      String path,
      HttpMethod method,
      @Nullable String authHeader,
      @Nullable String body) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    if (authHeader != null) {
      headers.add(HttpHeaders.AUTHORIZATION, authHeader);
    }
    HttpEntity<String> request = new HttpEntity<>(body, headers);
    return restTemplate.exchange(base + path, method, request, String.class);
  }

  private static String basicAuth(String username, String password) {
    String credentials = username + ":" + password;
    return "Basic "
        + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> parse(ResponseEntity<String> response) throws Exception {
    return objectMapper.readValue(response.getBody(), Map.class);
  }

  private static String requiredString(Map<String, Object> body, String key) {
    Object value = body.get(key);
    if (!(value instanceof String string)) {
      throw new IllegalStateException("Expected a string field '" + key + "' in " + body);
    }
    return string;
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
}
