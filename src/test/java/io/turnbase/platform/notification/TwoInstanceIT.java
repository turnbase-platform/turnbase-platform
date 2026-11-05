package io.turnbase.platform.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.turnbase.platform.PostgresIntegrationTest;
import io.turnbase.platform.TurnBaseApplication;
import io.turnbase.platform.session.SessionRevokedEvent;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code IMP-M18-08} / {@code load-testing-phase-b.md} §4.1, §4.2: two Platform application
 * contexts on one Postgres and one Redis behave as one instance for everything user-scoped. Lives
 * in {@code notification} because the SSE registry is package-private.
 *
 * <p>Keeps its own boot: it needs two live contexts at once, which the one-context cache never
 * holds. Both are booted once for the class; every test registers its own user. (IMP-M20-10)
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TwoInstanceIT extends PostgresIntegrationTest {

  private static final int API_CAPACITY = 10;

  private final JsonMapper objectMapper = JsonMapper.builder().build();
  private final RestTemplate http = new RestTemplate(new JdkClientHttpRequestFactory());
  private final ThreadPoolTaskScheduler scheduler = scheduler();

  private @Nullable ConfigurableApplicationContext a;
  private @Nullable ConfigurableApplicationContext b;
  private String urlA = "";
  private String urlB = "";

  {
    http.setErrorHandler(new NoOpResponseErrorHandler());
  }

  @BeforeAll
  void boot() {
    a = boot("a");
    b = boot("b");
    urlA = "http://127.0.0.1:" + port(a());
    urlB = "http://127.0.0.1:" + port(b());
  }

  @AfterAll
  void stop() {
    if (b != null) {
      b.close();
    }
    if (a != null) {
      a.close();
    }
    scheduler.shutdown();
  }

  @Test
  void anAlertPublishedOnOneInstanceReachesTheStreamOnTheOther() throws Exception {
    Registered user = register(urlA);
    try (OpenStream stream = openStream(a(), urlA, user.cookie())) {
      b().getBean(AlertPublisher.class)
          .publish(user.userId(), "INVITATION_ACCEPTED", Map.of("user", "bob"));

      String frame = stream.awaitFrame("INVITATION_ACCEPTED");
      assertThat(frame).contains("event:alert").contains("\"user\":\"bob\"");
    }
  }

  @Test
  void aPersistedNotificationCommittedOnOneInstanceIsPushedLiveByTheOther() throws Exception {
    Registered sender = register(urlB);
    Registered recipient = register(urlA);
    try (OpenStream stream = openStream(a(), urlA, recipient.cookie())) {
      ResponseEntity<String> sent =
          post(
              urlB + "/api/friend-requests",
              sender.cookie(),
              "{\"recipientUsername\":\"%s\"}".formatted(recipient.username()));
      assertThat(sent.getStatusCode()).isEqualTo(HttpStatus.CREATED);

      assertThat(stream.awaitFrame("FRIEND_REQUEST")).contains("event:notification");
    }
  }

  @Test
  void aRevocationOnOneInstanceClosesTheSocketAndStreamOnTheOther() throws Exception {
    Registered user = register(urlA);
    StompSession socket = connect(urlA, user.cookie());
    try (OpenStream stream = openStream(a(), urlA, user.cookie())) {
      UUID sessionId = sessionIdOf(user.userId());

      b().publishEvent(new SessionRevokedEvent(sessionId, user.userId(), "DISPLACED"));

      assertThat(stream.awaitFrame("SESSION_DISPLACED")).contains("event:alert");
      await("the WebSocket on the other instance closes", () -> !socket.isConnected());
      await("the stream on the other instance ends", stream::ended);
    }
  }

  @Test
  void theFifthWebSocketIsRefusedWhenTheFourAreSplitAcrossBothInstances() throws Exception {
    Registered user = register(urlA);
    StompSession first = connect(urlA, user.cookie());
    connect(urlB, user.cookie());
    connect(urlA, user.cookie());
    connect(urlB, user.cookie());

    assertThatThrownBy(() -> connect(urlA, user.cookie())).isInstanceOf(ExecutionException.class);
    assertThatThrownBy(() -> connect(urlB, user.cookie())).isInstanceOf(ExecutionException.class);

    first.disconnect();
    await("a freed slot is usable on the other instance", () -> canConnect(urlB, user.cookie()));
  }

  @Test
  void theApiRateLimitTierIsOneBucketAcrossInstances() {
    Registered user = register(urlA);

    for (int i = 0; i < API_CAPACITY; i++) {
      String base = i % 2 == 0 ? urlA : urlB;
      assertThat(get(base + "/api/auth/session", user.cookie()).getStatusCode())
          .isEqualTo(HttpStatus.OK);
    }

    assertThat(get(urlA + "/api/auth/session", user.cookie()).getStatusCode())
        .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    assertThat(get(urlB + "/api/auth/session", user.cookie()).getStatusCode())
        .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
  }

  @Test
  void aShedLockedJobRunsOnOneInstanceWhileTheOtherHoldsTheLock() {
    JdbcClient jdbc = a().getBean(JdbcClient.class);
    UUID old = insertOldNotification(jdbc);
    LockProvider provider = a().getBean(LockProvider.class);
    SimpleLock held =
        provider
            .lock(
                new LockConfiguration(
                    java.time.Instant.now(),
                    "notificationPurge",
                    java.time.Duration.ofMinutes(5),
                    java.time.Duration.ZERO))
            .orElseThrow();

    b().getBean(NotificationPurgeJob.class).run();
    assertThat(notificationExists(jdbc, old)).as("skipped while A holds the lock").isTrue();

    held.unlock();
    b().getBean(NotificationPurgeJob.class).run();
    assertThat(notificationExists(jdbc, old)).as("runs once the lock is free").isFalse();
  }

  // ---------------------------------------------------------------------------------------------

  private ConfigurableApplicationContext boot(String name) {
    return SpringApplication.run(
        TurnBaseApplication.class,
        "--server.port=0",
        "--management.server.port=0",
        "--spring.application.name=turnbase-" + name,
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
        "--turnbase.rate-limit.api.capacity=" + API_CAPACITY,
        "--turnbase.rate-limit.api.window=1h",
        "--turnbase.rate-limit.minting.guest.capacity=1000000",
        "--turnbase.rate-limit.minting.registration.capacity=1000000",
        "--turnbase.rate-limit.auth.failures-before-lockout=1000000",
        "--turnbase.rate-limit.ws.connection.capacity=1000000",
        "--turnbase.rate-limit.ws.connection.refill=1000000/1s",
        "--turnbase.jobs.deadline-sweep.interval=1h",
        "--turnbase.jobs.reconcile.interval=1h",
        "--turnbase.jobs.notification-purge.interval=1h",
        "--turnbase.jobs.expiry-purge.interval=1h",
        "--turnbase.retention.notification=30d");
  }

  private ConfigurableApplicationContext a() {
    return Objects.requireNonNull(a);
  }

  private ConfigurableApplicationContext b() {
    return Objects.requireNonNull(b);
  }

  private static int port(ConfigurableApplicationContext context) {
    return Integer.parseInt(
        Objects.requireNonNull(context.getEnvironment().getProperty("local.server.port")));
  }

  private record Registered(UUID userId, String username, String cookie) {}

  private Registered register(String baseUrl) {
    String username = "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
    ResponseEntity<String> response =
        http.exchange(
            baseUrl + "/api/auth/register",
            HttpMethod.POST,
            new HttpEntity<>(
                """
                {"username":"%s","email":"%s@example.test","password":"correct horse battery","confirmPassword":"correct horse battery"}
                """
                    .formatted(username, username),
                headers),
            String.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    Map<?, ?> body = objectMapper.readValue(response.getBody(), Map.class);
    String cookie =
        Objects.requireNonNull(response.getHeaders().get(HttpHeaders.SET_COOKIE)).stream()
            .filter(c -> c.startsWith("turnbase_session="))
            .findFirst()
            .orElseThrow()
            .split(";", 2)[0];
    return new Registered(UUID.fromString((String) body.get("userId")), username, cookie);
  }

  private ResponseEntity<String> get(String url, String cookie) {
    HttpHeaders headers = new HttpHeaders();
    headers.add(HttpHeaders.COOKIE, cookie);
    return http.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), String.class);
  }

  private ResponseEntity<String> post(String url, String cookie, String body) {
    HttpHeaders headers = new HttpHeaders();
    headers.add(HttpHeaders.COOKIE, cookie);
    headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
    return http.exchange(url, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
  }

  private UUID sessionIdOf(UUID userId) {
    return a().getBean(JdbcClient.class)
        .sql("select session_id from user_session where user_id = :id and revoked_at is null")
        .param("id", userId)
        .query(UUID.class)
        .single();
  }

  private StompSession connect(String baseUrl, String cookie) throws Exception {
    WebSocketHttpHeaders handshake = new WebSocketHttpHeaders();
    handshake.add(HttpHeaders.COOKIE, cookie);
    WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
    client.setMessageConverter(new MappingJackson2MessageConverter());
    client.setTaskScheduler(scheduler);
    return client
        .connectAsync(
            baseUrl.replace("http://", "ws://") + "/ws",
            handshake,
            new StompSessionHandlerAdapter() {})
        .get(5, TimeUnit.SECONDS);
  }

  private boolean canConnect(String baseUrl, String cookie) {
    try {
      connect(baseUrl, cookie).disconnect();
      return true;
    } catch (Exception e) {
      return false;
    }
  }

  private static void await(String what, BooleanSupplier condition) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() > deadline) {
        throw new AssertionError("timed out: " + what);
      }
      Thread.sleep(50);
    }
  }

  private static ThreadPoolTaskScheduler scheduler() {
    ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    scheduler.setThreadNamePrefix("two-instance-");
    scheduler.initialize();
    return scheduler;
  }

  private UUID insertOldNotification(JdbcClient jdbc) {
    UUID userId = register(urlA).userId();
    UUID id = UUID.randomUUID();
    jdbc.sql(
            "insert into notification (notification_id, user_id, type, payload, created_at)"
                + " values (:id, :user, 'GAME_APPROVED', '{}'::jsonb, now() - interval '40 days')")
        .param("id", id)
        .param("user", userId)
        .update();
    return id;
  }

  private static boolean notificationExists(JdbcClient jdbc, UUID id) {
    return jdbc.sql("select count(*) from notification where notification_id = :id")
            .param("id", id)
            .query(Long.class)
            .single()
        == 1L;
  }

  /**
   * The stream is registered on the server before it is used, as in {@code
   * NotificationControllerIT}.
   */
  private OpenStream openStream(ConfigurableApplicationContext on, String baseUrl, String cookie)
      throws Exception {
    NotificationEmitterRegistry registry = on.getBean(NotificationEmitterRegistry.class);
    int before = registry.activeConnectionCount();
    OpenStream stream = new OpenStream(baseUrl, cookie);
    await("the stream registers", () -> registry.activeConnectionCount() > before);
    return stream;
  }

  private static final class OpenStream implements AutoCloseable {
    private final LinkedBlockingQueue<String> frames = new LinkedBlockingQueue<>();
    private volatile boolean ended;

    OpenStream(String baseUrl, String cookie) {
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create(baseUrl + "/api/notifications/stream"))
              .header("Cookie", cookie)
              .GET()
              .build();
      Thread reader =
          new Thread(
              () -> {
                try {
                  HttpResponse<InputStream> response =
                      HttpClient.newHttpClient()
                          .send(request, HttpResponse.BodyHandlers.ofInputStream());
                  BufferedReader lines =
                      new BufferedReader(
                          new InputStreamReader(response.body(), StandardCharsets.UTF_8));
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
                ended = true;
              });
      reader.setDaemon(true);
      reader.start();
    }

    boolean ended() {
      return ended;
    }

    String awaitFrame(String type) throws InterruptedException {
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
      while (System.nanoTime() < deadline) {
        String frame = frames.poll(100, TimeUnit.MILLISECONDS);
        if (frame != null && frame.contains("\"type\":\"" + type + "\"")) {
          return frame;
        }
      }
      throw new AssertionError("no " + type + " frame arrived");
    }

    @Override
    public void close() {
      // daemon thread; the application context closing ends the connection
    }
  }

  private static final class NoOpResponseErrorHandler
      implements org.springframework.web.client.ResponseErrorHandler {
    @Override
    public boolean hasError(org.springframework.http.client.ClientHttpResponse response) {
      return false;
    }
  }
}
