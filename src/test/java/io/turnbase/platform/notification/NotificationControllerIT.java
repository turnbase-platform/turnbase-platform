package io.turnbase.platform.notification;

import static org.assertj.core.api.Assertions.assertThat;

import io.turnbase.platform.PostgresIntegrationTest;
import io.turnbase.platform.SharedApplication;
import io.turnbase.platform.UsesSharedApplication;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code notification-rest-api.md} §§2-6's five endpoints, plus {@code sse-notifications.md}'s
 * offline-catch-up half of the stream endpoint end to end against a real Postgres.
 */
@UsesSharedApplication
class NotificationControllerIT extends PostgresIntegrationTest {

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
  void listReturnsOnlyThePrincipalsNotificationsNewestFirst() throws Exception {
    Registered owner = registerUser();
    Registered other = registerUser();
    UUID older = insertNotification(owner.userId(), "FRIEND_ADDED", Map.of());
    UUID newer = insertNotification(owner.userId(), "GAME_APPROVED", Map.of());
    insertNotification(other.userId(), "GAME_APPROVED", Map.of());

    ResponseEntity<String> response = getWithCookie("/api/notifications", owner.cookie());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    Map<String, Object> body = parse(response);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> content =
        java.util.Objects.requireNonNull((List<Map<String, Object>>) body.get("content"));
    assertThat(content).hasSize(2);
    assertThat(content.get(0).get("notificationId")).isEqualTo(newer.toString());
    assertThat(content.get(1).get("notificationId")).isEqualTo(older.toString());
    @SuppressWarnings("unchecked")
    Map<String, Object> pagination =
        java.util.Objects.requireNonNull((Map<String, Object>) body.get("pagination"));
    assertThat(pagination.get("totalElements")).isEqualTo(2);
  }

  @Test
  void listWithNoSessionIsUnauthenticated() {
    ResponseEntity<String> response =
        restTemplate.exchange(baseUrl + "/api/notifications", HttpMethod.GET, null, String.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void unreadCountCountsOnlyUnreadRows() throws Exception {
    Registered owner = registerUser();
    UUID read = insertNotification(owner.userId(), "GAME_APPROVED", Map.of());
    insertNotification(owner.userId(), "GAME_APPROVED", Map.of());
    markReadDirectly(read);

    ResponseEntity<String> response =
        getWithCookie("/api/notifications/unread-count", owner.cookie());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(parse(response).get("unreadCount")).isEqualTo(1);
  }

  @Test
  void markReadTransitionsOnceAndIsANoOpAfterThat() throws Exception {
    Registered owner = registerUser();
    UUID id = insertNotification(owner.userId(), "GAME_APPROVED", Map.of());

    ResponseEntity<String> first =
        patchWithCookie("/api/notifications/" + id, owner.cookie(), "{\"read\":true}");
    ResponseEntity<String> second =
        patchWithCookie("/api/notifications/" + id, owner.cookie(), "{\"read\":true}");

    assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(parse(first).get("readAt")).isNotNull();
    assertThat(parse(first).get("readAt")).isEqualTo(parse(second).get("readAt"));
  }

  @Test
  void markReadWithFalseIsRejected() throws Exception {
    Registered owner = registerUser();
    UUID id = insertNotification(owner.userId(), "GAME_APPROVED", Map.of());

    ResponseEntity<String> response =
        patchWithCookie("/api/notifications/" + id, owner.cookie(), "{\"read\":false}");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
  }

  @Test
  void markReadOnAnotherUsersNotificationIsNotFound() throws Exception {
    Registered owner = registerUser();
    Registered other = registerUser();
    UUID id = insertNotification(owner.userId(), "GAME_APPROVED", Map.of());

    ResponseEntity<String> response =
        patchWithCookie("/api/notifications/" + id, other.cookie(), "{\"read\":true}");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void markAllReadClearsEveryUnreadRowForThePrincipalOnly() throws Exception {
    Registered owner = registerUser();
    Registered other = registerUser();
    UUID a = insertNotification(owner.userId(), "GAME_APPROVED", Map.of());
    UUID b = insertNotification(owner.userId(), "GAME_APPROVED", Map.of());
    UUID untouched = insertNotification(other.userId(), "GAME_APPROVED", Map.of());

    ResponseEntity<String> response =
        postWithCookie("/api/notifications/read-all", owner.cookie(), null);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    assertThat(readAtOf(a)).isNotNull();
    assertThat(readAtOf(b)).isNotNull();
    assertThat(readAtOf(untouched)).isNull();
  }

  @Test
  void deleteRemovesTheRowAndIsIdempotent() throws Exception {
    Registered owner = registerUser();
    UUID id = insertNotification(owner.userId(), "GAME_APPROVED", Map.of());

    ResponseEntity<String> first = deleteWithCookie("/api/notifications/" + id, owner.cookie());
    ResponseEntity<String> second = deleteWithCookie("/api/notifications/" + id, owner.cookie());

    assertThat(first.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    assertThat(second.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    Long rows =
        jdbcClient
            .sql("select count(*) from notification where notification_id = :id")
            .param("id", id)
            .query(Long.class)
            .single();
    assertThat(rows).isEqualTo(0);
  }

  @Test
  void deletingAnotherUsersNotificationLeavesItUntouched() throws Exception {
    Registered owner = registerUser();
    Registered other = registerUser();
    UUID id = insertNotification(owner.userId(), "GAME_APPROVED", Map.of());

    ResponseEntity<String> response = deleteWithCookie("/api/notifications/" + id, other.cookie());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    Long rows =
        jdbcClient
            .sql("select count(*) from notification where notification_id = :id")
            .param("id", id)
            .query(Long.class)
            .single();
    assertThat(rows).isEqualTo(1);
  }

  @Test
  void theStreamDeliversUndeliveredNotificationsOnConnectAndStampsThemDelivered() throws Exception {
    Registered owner = registerUser();
    UUID id = insertNotification(owner.userId(), "FRIEND_REQUEST", Map.of("fromUser", "alice"));

    String frame = readOneSseEvent(owner.cookie());

    assertThat(frame).contains("event:notification");
    assertThat(frame).contains("\"type\":\"FRIEND_REQUEST\"");
    assertThat(frame).contains("\"notificationId\":\"" + id + "\"");
    assertThat(frame).contains("\"fromUser\":\"alice\"");
    Long deliveredCount =
        jdbcClient
            .sql(
                "select count(*) from notification where notification_id = :id and delivered_at is not null")
            .param("id", id)
            .query(Long.class)
            .single();
    assertThat(deliveredCount).isEqualTo(1);
  }

  @Test
  void aFriendRequestIsPushedToAnAlreadyOpenStreamAndNotRepeatedOnReconnect() throws Exception {
    Registered sender = registerUser();
    Registered recipient = registerUser();
    try (OpenStream stream = openStream(recipient)) {
      ResponseEntity<String> sent =
          postWithCookie(
              "/api/friend-requests",
              sender.cookie(),
              "{\"recipientUsername\":\"%s\"}".formatted(recipient.username()));
      assertThat(sent.getStatusCode()).isEqualTo(HttpStatus.CREATED);

      String frame = stream.awaitFrame("FRIEND_REQUEST");
      assertThat(frame).contains("event:notification").contains("id:");
      assertThat(frame).contains("\"username\":\"" + sender.username() + "\"");
    }
    Long undelivered =
        jdbcClient
            .sql("select count(*) from notification where user_id = :id and delivered_at is null")
            .param("id", recipient.userId())
            .query(Long.class)
            .single();
    assertThat(undelivered).isZero();

    try (OpenStream again = openStream(recipient)) {
      assertThat(again.awaitFrameOrNull("FRIEND_REQUEST")).isNull();
    }
  }

  @Test
  void aRecipientWithNoOpenStreamIsLeftUndeliveredForTheNextOpen() throws Exception {
    Registered sender = registerUser();
    Registered recipient = registerUser();
    postWithCookie(
        "/api/friend-requests",
        sender.cookie(),
        "{\"recipientUsername\":\"%s\"}".formatted(recipient.username()));

    Long undelivered =
        jdbcClient
            .sql("select count(*) from notification where user_id = :id and delivered_at is null")
            .param("id", recipient.userId())
            .query(Long.class)
            .single();
    assertThat(undelivered).isEqualTo(1);
  }

  @Test
  void aRolledBackNotificationIsNeverPushed() throws Exception {
    Registered recipient = registerUser();
    ConfigurableApplicationContext booted = java.util.Objects.requireNonNull(context);
    TransactionTemplate tx = booted.getBean(TransactionTemplate.class);
    ApplicationEventPublisher publisher = booted;
    try (OpenStream stream = openStream(recipient)) {
      tx.executeWithoutResult(
          status -> {
            try {
              UUID id = insertNotification(recipient.userId(), "FRIEND_REQUEST", Map.of());
              publisher.publishEvent(new NotificationPersistedEvent(id, recipient.userId()));
            } catch (Exception e) {
              throw new IllegalStateException(e);
            }
            status.setRollbackOnly();
          });

      assertThat(stream.awaitFrameOrNull("FRIEND_REQUEST")).isNull();
    }
  }

  /**
   * Response headers are not committed until the first event, so "connected" is observed on the
   * server: the emitter is registered before any catch-up is written.
   */
  private OpenStream openStream(Registered user) throws Exception {
    NotificationEmitterRegistry registry =
        java.util.Objects.requireNonNull(context).getBean(NotificationEmitterRegistry.class);
    int before = registry.activeConnectionCount();
    OpenStream stream = new OpenStream(baseUrl, user.cookie());
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (registry.activeConnectionCount() <= before) {
      if (System.nanoTime() > deadline) {
        throw new AssertionError("the stream was never registered");
      }
      Thread.sleep(25);
    }
    return stream;
  }

  /** A stream held open in a background thread, collecting one string per SSE frame. */
  private static final class OpenStream implements AutoCloseable {
    private final LinkedBlockingQueue<String> frames = new LinkedBlockingQueue<>();
    private final Thread reader;
    private volatile @Nullable InputStream body;

    OpenStream(String baseUrl, String cookie) {
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create(baseUrl + "/api/notifications/stream"))
              .header("Cookie", cookie)
              .GET()
              .build();
      reader =
          new Thread(
              () -> {
                try {
                  HttpResponse<InputStream> response =
                      HttpClient.newHttpClient()
                          .send(request, HttpResponse.BodyHandlers.ofInputStream());
                  InputStream stream = response.body();
                  body = stream;
                  BufferedReader lines =
                      new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
                  StringBuilder frame = new StringBuilder();
                  String line;
                  while ((line = lines.readLine()) != null) {
                    if (line.isEmpty()) {
                      frames.add(frame.toString());
                      frame.setLength(0);
                    } else {
                      frame.append(line.replace(" ", "")).append('\n');
                    }
                  }
                } catch (Exception e) {
                  // closed by the test
                }
              });
      reader.setDaemon(true);
      reader.start();
    }

    String awaitFrame(String type) throws InterruptedException {
      String frame = awaitFrameOrNull(type, 10);
      if (frame == null) {
        throw new AssertionError("no " + type + " frame arrived");
      }
      return frame;
    }

    @Nullable String awaitFrameOrNull(String type) throws InterruptedException {
      return awaitFrameOrNull(type, 2);
    }

    private @Nullable String awaitFrameOrNull(String type, int seconds)
        throws InterruptedException {
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
      while (System.nanoTime() < deadline) {
        String frame = frames.poll(100, TimeUnit.MILLISECONDS);
        if (frame != null && frame.contains("\"type\":\"" + type + "\"")) {
          return frame;
        }
      }
      return null;
    }

    @Override
    public void close() throws java.io.IOException {
      InputStream open = body;
      if (open != null) {
        open.close();
      }
    }
  }

  private String readOneSseEvent(String cookie) throws Exception {
    HttpClient httpClient = HttpClient.newHttpClient();
    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/api/notifications/stream"))
            .header("Cookie", cookie)
            .timeout(Duration.ofSeconds(10))
            .GET()
            .build();

    CompletableFuture<String> future =
        CompletableFuture.supplyAsync(
            () -> {
              try {
                HttpResponse<InputStream> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
                try (InputStream body = response.body();
                    BufferedReader reader =
                        new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
                  StringBuilder captured = new StringBuilder();
                  String line;
                  while ((line = reader.readLine()) != null) {
                    captured.append(line.replace(" ", "")).append('\n');
                    if (line.startsWith("data:")) {
                      break;
                    }
                  }
                  return captured.toString();
                }
              } catch (Exception e) {
                throw new RuntimeException(e);
              }
            });
    return future.get(10, TimeUnit.SECONDS);
  }

  private UUID insertNotification(UUID userId, String type, Map<String, Object> payload)
      throws Exception {
    UUID id = UUID.randomUUID();
    jdbcClient
        .sql(
            "insert into notification (notification_id, user_id, type, payload) values (:id, :userId, :type, :payload::jsonb)")
        .param("id", id)
        .param("userId", userId)
        .param("type", type)
        .param("payload", objectMapper.writeValueAsString(payload))
        .update();
    return id;
  }

  private void markReadDirectly(UUID id) {
    jdbcClient
        .sql("update notification set read_at = now() where notification_id = :id")
        .param("id", id)
        .update();
  }

  private @Nullable Timestamp readAtOf(UUID id) {
    return jdbcClient
        .sql("select read_at from notification where notification_id = :id")
        .param("id", id)
        .query((rs, rowNum) -> new ReadAtHolder(rs.getTimestamp("read_at")))
        .single()
        .readAt();
  }

  private record ReadAtHolder(@Nullable Timestamp readAt) {}

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

  private ResponseEntity<String> postWithCookie(String path, String cookie, @Nullable String body) {
    return withBody(path, HttpMethod.POST, cookie, body);
  }

  private ResponseEntity<String> getWithCookie(String path, String cookie) {
    return withBody(path, HttpMethod.GET, cookie, null);
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
