package io.turnbase.platform.upload;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.turnbase.platform.PostgresIntegrationTest;
import io.turnbase.platform.TurnBaseApplication;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
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
 * {@code file-uploads.md}'s three-step flow end to end, and {@code bundle-format.md} §3.4's
 * synchronous/asynchronous failure split (IMP-M4-03's Done-when clause 5), the sweep's lease
 * recovery (clause 2), and §7.4's slot reclamation.
 */
class VersionUploadControllerIT extends PostgresIntegrationTest {

  private final ObjectMapper objectMapper = new ObjectMapper();
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

  @AfterEach
  void stopApplication() {
    if (context != null) {
      context.close();
    }
  }

  // ---------------------------------------------------------------- Phase A: synchronous 422/409

  @Test
  void versionFormatFailureIsSynchronous422() throws Exception {
    Registered dev = registerUser();
    UUID gameId = seedOfficialGame();

    ResponseEntity<String> response = createSlot(gameId, dev.cookie(), "not-a-version", 1000);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    Map<String, Object> body = parse(response);
    assertThat(checksOf(body)).contains("version.format");
  }

  @Test
  void versionOrderingFailureIsSynchronous422() throws Exception {
    Registered dev = registerUser();
    UUID gameId = seedOfficialGame();
    activateTrivialVersion(gameId, "1.5.0");

    ResponseEntity<String> response = createSlot(gameId, dev.cookie(), "1.0.0", 1000);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    assertThat(checksOf(parse(response))).contains("version.ordering");
  }

  @Test
  void declaredSizeOverTheCapIsSynchronous422() throws Exception {
    Registered dev = registerUser();
    UUID gameId = seedOfficialGame();

    ResponseEntity<String> response = createSlot(gameId, dev.cookie(), "1.0.0", 999_999_999_999L);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    assertThat(checksOf(parse(response))).contains("bundle.declaredSize");
  }

  @Test
  void aSecondSlotForAnOpenPendingVersionIsConflict() throws Exception {
    Registered dev = registerUser();
    UUID gameId = seedOfficialGame();
    createSlot(gameId, dev.cookie(), "1.0.0", 1000);

    ResponseEntity<String> response = createSlot(gameId, dev.cookie(), "1.0.0", 1000);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
  }

  // ---------------------------------------------------------------- Phase B: synchronous confirm
  // outcomes

  @Test
  void confirmingBeforeTheObjectIsUploadedIsSynchronous422() throws Exception {
    Registered dev = registerUser();
    UUID gameId = seedOfficialGame();
    Map<String, Object> slot = parse(createSlot(gameId, dev.cookie(), "1.0.0", 1000));
    UUID uploadId = UUID.fromString(requiredString(slot, "uploadId"));

    ResponseEntity<String> response = confirm(gameId, uploadId, dev.cookie());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    assertThat(checksOf(parse(response))).contains("bundle.storedSize");
  }

  @Test
  void confirmingAfterTheSlotExpiresIsGone() throws Exception {
    Registered dev = registerUser();
    UUID gameId = seedOfficialGame();
    Map<String, Object> slot = parse(createSlot(gameId, dev.cookie(), "1.0.0", 1000));
    UUID uploadId = UUID.fromString(requiredString(slot, "uploadId"));
    // Backdate the slot's own expiry rather than shortening turnbase.uploads.slot-ttl for the
    // whole test class — the pre-signed PUT URL's expiry is computed from that same TTL
    // (bundle-format.md §7.3: "the same instant, computed once"), so a class-wide short TTL
    // starves every other test's PUT of enough time to land before its URL expires too.
    jdbcClient
        .sql("update game_version set upload_expires_at = :expired where version_id = :id")
        .param("expired", Timestamp.from(Instant.now().minusSeconds(60)))
        .param("id", uploadId)
        .update();

    ResponseEntity<String> response = confirm(gameId, uploadId, dev.cookie());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.GONE);
    VersionResourceView view = getVersion(gameId, uploadId, dev.cookie());
    assertThat(view.status()).isEqualTo("EXPIRED");
  }

  // ---------------------------------------------------------------- Phases B-G: async, never an
  // HTTP error

  @Test
  void aFullyValidBundleActivates() throws Exception {
    Registered dev = registerUser();
    UUID gameId = seedOfficialGame();
    byte[] bundle = validBundle("1.0.0");
    Map<String, Object> slot = parse(createSlot(gameId, dev.cookie(), "1.0.0", bundle.length));
    UUID uploadId = UUID.fromString(requiredString(slot, "uploadId"));
    putBundle(requiredString(slot, "uploadUrl"), bundle);

    ResponseEntity<String> confirmResponse = confirm(gameId, uploadId, dev.cookie());
    assertThat(confirmResponse.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

    VersionResourceView finalView = pollUntilTerminal(gameId, uploadId, dev.cookie());

    assertThat(finalView.status()).as("errors=%s", finalView.errors()).isEqualTo("ACTIVE");
    assertThat(finalView.errors()).isEmpty();
    String activeVersionTitle =
        jdbcClient
            .sql("select title from game where game_id = :id")
            .param("id", gameId)
            .query(String.class)
            .single();
    assertThat(activeVersionTitle).isEqualTo("Chess");
  }

  @Test
  void asyncFailuresSurfaceOnlyAsTheVersionResourceNeverAsAnHttpError() throws Exception {
    Registered dev = registerUser();
    UUID gameId = seedOfficialGame();
    byte[] bundle = bundleMissingFrontendEntry("1.0.0");
    Map<String, Object> slot = parse(createSlot(gameId, dev.cookie(), "1.0.0", bundle.length));
    UUID uploadId = UUID.fromString(requiredString(slot, "uploadId"));
    putBundle(requiredString(slot, "uploadUrl"), bundle);

    ResponseEntity<String> confirmResponse = confirm(gameId, uploadId, dev.cookie());
    assertThat(confirmResponse.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

    VersionResourceView finalView = pollUntilTerminal(gameId, uploadId, dev.cookie());

    assertThat(finalView.status()).isEqualTo("FAILED");
    assertThat(finalView.httpStatus()).isEqualTo(200); // never a 4xx, even though validation failed
    assertThat(finalView.errors())
        .anySatisfy(e -> assertThat(e).isEqualTo("bundle.paths.frontend"));
  }

  // ---------------------------------------------------------------- §7.4 reclamation

  @Test
  void aFailedVersionNumberIsReclaimable() throws Exception {
    Registered dev = registerUser();
    UUID gameId = seedOfficialGame();
    byte[] bad = bundleMissingFrontendEntry("1.0.0");
    Map<String, Object> slot = parse(createSlot(gameId, dev.cookie(), "1.0.0", bad.length));
    UUID uploadId = UUID.fromString(requiredString(slot, "uploadId"));
    putBundle(requiredString(slot, "uploadUrl"), bad);
    confirm(gameId, uploadId, dev.cookie());
    pollUntilTerminal(gameId, uploadId, dev.cookie());

    ResponseEntity<String> reclaimed = createSlot(gameId, dev.cookie(), "1.0.0", 1000);

    assertThat(reclaimed.getStatusCode()).isEqualTo(HttpStatus.CREATED);
  }

  // ---------------------------------------------------------------- §7.2 duty 2: the sweep
  // un-burns a stuck lease

  @Test
  void theSweepFreesAnAbandonedLeaseAndTheVersionNumberCanBeRetried() throws Exception {
    Registered dev = registerUser();
    UUID gameId = seedOfficialGame();
    UUID versionId = UUID.randomUUID();
    jdbcClient
        .sql(
            "insert into game_version (version_id, game_id, version, status, bundle_object_key,"
                + " declared_size_bytes, upload_expires_at, claimed_by, claimed_until)"
                + " values (:id, :gameId, '1.0.0', 'PROCESSING', 'bundles/x/1.0.0/bundle.zip', 1000,"
                + " :expires, 'dead-worker', :claimedUntil)")
        .param("id", versionId)
        .param("gameId", gameId)
        .param("expires", Timestamp.from(Instant.now().plusSeconds(3600)))
        .param("claimedUntil", Timestamp.from(Instant.now().minusSeconds(3600))) // already expired
        .update();

    var context = this.context;
    if (context == null) {
      throw new IllegalStateException("Application context was not booted");
    }
    context.getBean(UploadSlotCleanupJob.class).run();

    String status =
        jdbcClient
            .sql("select status from game_version where version_id = :id")
            .param("id", versionId)
            .query(String.class)
            .single();
    assertThat(status).isEqualTo("FAILED");

    ResponseEntity<String> reclaimed = createSlot(gameId, dev.cookie(), "1.0.0", 1000);
    assertThat(reclaimed.getStatusCode()).isEqualTo(HttpStatus.CREATED);
  }

  // ---------------------------------------------------------------- helpers

  private ResponseEntity<String> createSlot(
      UUID gameId, String cookie, String version, long bundleSize) {
    return postWithCookie(
        "/api/games/" + gameId + "/versions",
        cookie,
        "{\"version\":\"%s\",\"bundleSize\":%d}".formatted(version, bundleSize));
  }

  private ResponseEntity<String> confirm(UUID gameId, UUID uploadId, String cookie) {
    return postWithCookie(
        "/api/games/" + gameId + "/versions/" + uploadId + "/confirm", cookie, "");
  }

  private VersionResourceView getVersion(UUID gameId, UUID uploadId, String cookie)
      throws Exception {
    ResponseEntity<String> response =
        getWithCookie("/api/games/" + gameId + "/versions/" + uploadId, cookie);
    Map<String, Object> body = parse(response);
    return new VersionResourceView(
        response.getStatusCode().value(), requiredString(body, "status"), checksOf(body));
  }

  private VersionResourceView pollUntilTerminal(UUID gameId, UUID uploadId, String cookie)
      throws Exception {
    Instant deadline = Instant.now().plus(Duration.ofSeconds(20));
    VersionResourceView last = getVersion(gameId, uploadId, cookie);
    while (Instant.now().isBefore(deadline)
        && (last.status().equals("PENDING") || last.status().equals("PROCESSING"))) {
      Thread.sleep(200);
      last = getVersion(gameId, uploadId, cookie);
    }
    return last;
  }

  private record VersionResourceView(int httpStatus, String status, List<String> errors) {}

  private void putBundle(String presignedUrl, byte[] bytes) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
    // The URI overload, not the String-template overload: RestTemplate treats a String URL as a
    // URI *template* and re-encodes it, corrupting the pre-signed query string (X-Amz-Signature
    // etc. are already percent-encoded and must not be touched again).
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

  private UUID seedOfficialGame() {
    UUID gameId = UUID.randomUUID();
    jdbcClient
        .sql(
            "insert into game (game_id, owner_user_id, title, short_description, badge, listed) values (:id, null, 'Untitled', 'placeholder', 'OFFICIAL', false)")
        .param("id", gameId)
        .update();
    return gameId;
  }

  /**
   * Seeds an already-ACTIVE version directly (bypassing the pipeline) purely so a later slot
   * request has an active version to compare against — mirrors {@code
   * RoomInvitationControllerIT#seedTwoPlayerGame}'s established precedent for prerequisite state no
   * endpoint in this repository can create yet.
   */
  private void activateTrivialVersion(UUID gameId, String version) {
    UUID versionId = UUID.randomUUID();
    jdbcClient
        .sql(
            "insert into game_version (version_id, game_id, version, status, manifest, declared_size_bytes,"
                + " upload_expires_at, min_players, max_players, supports_ai, supports_spectators,"
                + " supports_mid_game_join, activated_at) values (:id, :gameId, :version, 'ACTIVE', '{}'::jsonb,"
                + " 1024, now(), 2, 2, false, false, false, now())")
        .param("id", versionId)
        .param("gameId", gameId)
        .param("version", version)
        .update();
    jdbcClient
        .sql("update game set active_version_id = :vid where game_id = :id")
        .param("vid", versionId)
        .param("id", gameId)
        .update();
  }

  private byte[] validBundle(String version) {
    Map<String, byte[]> classes = compileChessClasses();
    byte[] backendJar =
        zipOf(
            Map.of(
                "com/example/ChessGame.class",
                classes.get("com/example/ChessGame"),
                "com/example/ChessState.class",
                classes.get("com/example/ChessState")));
    String manifest = chessManifest(version, "frontend/main.js");
    return zipOf(
        Map.of(
            "manifest.json", manifest.getBytes(UTF_8),
            "backend/game.jar", backendJar,
            "frontend/main.js", "console.log('hi');".getBytes(UTF_8)));
  }

  private byte[] bundleMissingFrontendEntry(String version) {
    Map<String, byte[]> classes = compileChessClasses();
    byte[] backendJar =
        zipOf(
            Map.of(
                "com/example/ChessGame.class",
                classes.get("com/example/ChessGame"),
                "com/example/ChessState.class",
                classes.get("com/example/ChessState")));
    String manifest = chessManifest(version, "frontend/does-not-exist.js");
    return zipOf(Map.of("manifest.json", manifest.getBytes(UTF_8), "backend/game.jar", backendJar));
  }

  private static Map<String, byte[]> compileChessClasses() {
    return TestJavaCompiler.compile(
        true,
        Map.of(
            "io.turnbase.gdk.TurnBaseGame",
                "package io.turnbase.gdk; public interface TurnBaseGame<S> {}",
            "com.example.ChessState",
                "package com.example; public record ChessState(String board) {}",
            "com.example.ChessGame",
                "package com.example; public final class ChessGame implements io.turnbase.gdk.TurnBaseGame<ChessState> {"
                    + " public ChessGame() {} }"));
  }

  private static String chessManifest(String version, String frontendEntryPoint) {
    return """
        {
          "manifestVersion":"1",
          "title":"Chess",
          "shortDescription":"The classic game",
          "version":"%s",
          "gdkApiVersion":"0.3.0",
          "runtime":"jvm",
          "backend":{"path":"backend/game.jar","entryPoint":"com.example.ChessGame"},
          "frontend":{"entryPoint":"%s"},
          "tags":["strategy"],
          "capabilities":{"ai":false,"spectators":false,"midGameJoin":false},
          "teamTemplates":[{"size":{"min":1,"max":1},"count":{"min":2,"max":2}}]
        }
        """
        .formatted(version, frontendEntryPoint);
  }

  private static byte[] zipOf(Map<String, byte[]> entries) {
    try {
      ByteArrayOutputStream buffer = new ByteArrayOutputStream();
      try (ZipOutputStream zos = new ZipOutputStream(buffer, UTF_8)) {
        for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
          zos.putNextEntry(new ZipEntry(entry.getKey()));
          zos.write(entry.getValue());
          zos.closeEntry();
        }
      }
      return buffer.toByteArray();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static String requiredString(Map<String, Object> body, String key) {
    Object value = body.get(key);
    if (!(value instanceof String string)) {
      throw new IllegalStateException("Expected a string field '" + key + "' in " + body);
    }
    return string;
  }

  @SuppressWarnings("unchecked")
  private List<String> checksOf(Map<String, Object> body) {
    Object errors = body.get("errors");
    if (errors == null) {
      return List.of();
    }
    return ((List<Map<String, Object>>) errors).stream().map(e -> (String) e.get("check")).toList();
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
    UUID userId = UUID.fromString(requiredString(parse(response), "userId"));
    return new Registered(userId, username, sessionCookieHeader(response));
  }

  private ResponseEntity<String> post(String path, String body) {
    return withBody(path, HttpMethod.POST, null, body);
  }

  private ResponseEntity<String> postWithCookie(String path, String cookie, String body) {
    return withBody(path, HttpMethod.POST, cookie, body);
  }

  private ResponseEntity<String> getWithCookie(String path, String cookie) {
    return withBody(path, HttpMethod.GET, cookie, null);
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
    public void handleError(org.springframework.http.client.ClientHttpResponse response) {
      // Never called: hasError() always returns false so assertions see the real status.
    }
  }
}
