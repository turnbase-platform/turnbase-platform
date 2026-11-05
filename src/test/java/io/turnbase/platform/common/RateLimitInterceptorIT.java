package io.turnbase.platform.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.turnbase.platform.PostgresIntegrationTest;
import io.turnbase.platform.TurnBaseApplication;
import java.util.ArrayList;
import java.util.List;
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
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.web.client.RestTemplate;

/**
 * {@code IMP-M1-02}'s own Done-when clause, end to end against a real application boot, Postgres
 * and Redis: each tier's bucket/key/limit/window, the {@code error-handling.md} 429 shape with
 * {@code Retry-After}, and Tier 2's {@code X-RateLimit-*} headers. Every number here is a property
 * (rate-limiting.md § Configuration) — each test boots its own context with tiny, test-specific
 * overrides so the assertions are fast and deterministic, the same escape hatch {@code
 * local-environment.md} §8 describes for e2e, used here per-tier instead of wholesale.
 *
 * <p>Keeps its own boot: each test needs its own tiny rate-limit overrides and empty buckets, set
 * at startup. (IMP-M20-10)
 */
class RateLimitInterceptorIT extends PostgresIntegrationTest {

  private final RestTemplate restTemplate = new RestTemplate(new JdkClientHttpRequestFactory());

  private @Nullable ConfigurableApplicationContext context;
  private String baseUrl = "";

  {
    restTemplate.setErrorHandler(new NoOpResponseErrorHandler());
  }

  /**
   * Every test in this class runs from the same loopback IP against the same Redis container —
   * without this, an earlier test's bucket state (created under its own capacity/window) would
   * still be sitting in Redis under the identical key when the next test boots its own context with
   * different tier overrides.
   */
  @BeforeEach
  void flushRedis() throws Exception {
    REDIS.execInContainer("redis-cli", "FLUSHALL");
  }

  @AfterEach
  void stopApplication() {
    if (context != null) {
      context.close();
      context = null;
    }
  }

  @Test
  void tier5RejectsMintingOverTheCapAndReportsRetryAfter() {
    bootApplication(
        "--turnbase.rate-limit.minting.guest.capacity=2",
        "--turnbase.rate-limit.minting.guest.window=1h",
        "--turnbase.rate-limit.minting.registration.capacity=1000",
        "--turnbase.rate-limit.auth.failures-before-lockout=1000");

    assertThat(guest().getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(guest().getStatusCode()).isEqualTo(HttpStatus.CREATED);

    ResponseEntity<String> third = guest();

    assertThat(third.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    assertThat(third.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isNotNull();
  }

  @Test
  void tier5CountsOnlySuccessesNotValidationFailures() {
    bootApplication(
        "--turnbase.rate-limit.minting.registration.capacity=1",
        "--turnbase.rate-limit.minting.registration.window=1h",
        "--turnbase.rate-limit.minting.guest.capacity=1000",
        "--turnbase.rate-limit.auth.failures-before-lockout=1000");

    // A failed attempt (mismatched confirmPassword -> 400) must not consume the one token.
    ResponseEntity<String> invalid =
        register(uniqueUsername(), uniqueEmail(), "correct horse battery", "not the same at all");
    assertThat(invalid.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

    ResponseEntity<String> firstSuccess =
        register(uniqueUsername(), uniqueEmail(), "correct horse battery", "correct horse battery");
    assertThat(firstSuccess.getStatusCode()).isEqualTo(HttpStatus.CREATED);

    ResponseEntity<String> secondSuccess =
        register(uniqueUsername(), uniqueEmail(), "correct horse battery", "correct horse battery");
    assertThat(secondSuccess.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
  }

  @Test
  void tier1LocksOutAfterRepeatedFailuresAndRecoversAfterTheWindow() throws Exception {
    bootApplication(
        "--turnbase.rate-limit.auth.failures-before-lockout=2",
        "--turnbase.rate-limit.auth.initial-lockout=2s",
        "--turnbase.rate-limit.minting.registration.capacity=1000",
        "--turnbase.rate-limit.minting.guest.capacity=1000");

    String username = uniqueUsername();
    register(username, uniqueEmail(), "correct horse battery", "correct horse battery");

    assertThat(login(username, "wrong password one").getStatusCode())
        .isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(login(username, "wrong password two").getStatusCode())
        .isEqualTo(HttpStatus.UNAUTHORIZED);

    ResponseEntity<String> lockedOut = login(username, "correct horse battery");
    assertThat(lockedOut.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    assertThat(lockedOut.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isNotNull();

    Thread.sleep(2500);

    ResponseEntity<String> afterLockout = login(username, "correct horse battery");
    assertThat(afterLockout.getStatusCode()).isEqualTo(HttpStatus.OK);
  }

  @Test
  void tier1AdmitsNoMoreThanTheThresholdUnderConcurrentBadLogins() throws Exception {
    bootApplication(
        "--turnbase.rate-limit.auth.failures-before-lockout=5",
        "--turnbase.rate-limit.auth.initial-lockout=1h",
        "--turnbase.rate-limit.minting.registration.capacity=1000",
        "--turnbase.rate-limit.minting.guest.capacity=1000");

    String username = uniqueUsername();
    register(username, uniqueEmail(), "correct horse battery", "correct horse battery");

    int attempts = 40;
    java.util.concurrent.ExecutorService pool =
        java.util.concurrent.Executors.newFixedThreadPool(attempts);
    java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
    List<java.util.concurrent.Future<HttpStatusCode>> futures = new java.util.ArrayList<>();
    for (int i = 0; i < attempts; i++) {
      futures.add(
          pool.submit(
              () -> {
                go.await();
                return login(username, "wrong password").getStatusCode();
              }));
    }
    go.countDown();
    int unauthorized = 0;
    int locked = 0;
    for (var f : futures) {
      HttpStatusCode status = f.get();
      if (status.value() == 401) {
        unauthorized++;
      } else if (status.value() == 429) {
        locked++;
      }
    }
    pool.shutdown();

    assertThat(unauthorized).isEqualTo(5);
    assertThat(locked).isEqualTo(attempts - 5);
  }

  @Test
  void tier1IgnoresAClientSuppliedForwardedForFromAnUntrustedPeer() {
    bootApplication(
        "--turnbase.rate-limit.auth.failures-before-lockout=2",
        "--turnbase.rate-limit.minting.registration.capacity=1000",
        "--turnbase.rate-limit.minting.guest.capacity=1000");
    String username = uniqueUsername();
    register(username, uniqueEmail(), "correct horse battery", "correct horse battery");

    // A fresh forged address per attempt does not escape the caller's own bucket ...
    assertThat(loginFrom("203.0.113.1", username, "wrong password one").getStatusCode())
        .isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(loginFrom("203.0.113.2", username, "wrong password two").getStatusCode())
        .isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(loginFrom("203.0.113.3", username, "correct horse battery").getStatusCode())
        .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

    // ... and naming a victim's address cannot have counted against the victim's bucket.
    String victim = "198.51.100.77";
    assertThat(redisKeyCount("rate:auth:" + victim)).isZero();
    assertThat(redisKeyCount("rate:auth:203.0.113.*")).isZero();
  }

  @Test
  void tier1SuccessfulLoginsDoNotCountTowardTheLockout() {
    bootApplication(
        "--turnbase.rate-limit.auth.failures-before-lockout=2",
        "--turnbase.rate-limit.auth.initial-lockout=1h",
        "--turnbase.rate-limit.minting.registration.capacity=1000",
        "--turnbase.rate-limit.minting.guest.capacity=1000");

    String username = uniqueUsername();
    register(username, uniqueEmail(), "correct horse battery", "correct horse battery");

    for (int i = 0; i < 5; i++) {
      assertThat(login(username, "correct horse battery").getStatusCode()).isEqualTo(HttpStatus.OK);
    }
    assertThat(login(username, "wrong").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(login(username, "correct horse battery").getStatusCode()).isEqualTo(HttpStatus.OK);
  }

  @Test
  void tier1CountsFailuresOnEachOfTheThreeAuthMethodRoutes() {
    bootApplication(
        "--turnbase.rate-limit.auth.failures-before-lockout=3",
        "--turnbase.rate-limit.auth.initial-lockout=1h",
        "--turnbase.rate-limit.minting.registration.capacity=1000",
        "--turnbase.rate-limit.minting.guest.capacity=1000");
    String cookie =
        sessionCookieHeader(
            register(
                uniqueUsername(), uniqueEmail(), "correct horse battery", "correct horse battery"));
    String passwordBody =
        """
        {"password":"another password 1","confirmPassword":"another password 1"}
        """;

    // A registered account already holds PASSWORD (409), it is its only method (422), and no
    // Google link is in progress (404): three failures, one per route.
    assertThat(
            postWithCookie("/api/users/me/auth-methods/password", cookie, passwordBody)
                .getStatusCode())
        .isEqualTo(HttpStatus.CONFLICT);
    assertThat(deleteWithCookie("/api/users/me/auth-methods/PASSWORD", cookie).getStatusCode())
        .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    assertThat(postWithCookie("/api/users/me/auth-methods/google", cookie, null).getStatusCode())
        .isEqualTo(HttpStatus.NOT_FOUND);

    assertThat(deleteWithCookie("/api/users/me/auth-methods/PASSWORD", cookie).getStatusCode())
        .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
  }

  @Test
  void tier2EnforcesThePerUserCapAndEmitsHeaders() {
    bootApplication(
        "--turnbase.rate-limit.api.capacity=2",
        "--turnbase.rate-limit.api.window=1h",
        "--turnbase.rate-limit.minting.guest.capacity=1000",
        "--turnbase.rate-limit.minting.registration.capacity=1000",
        "--turnbase.rate-limit.auth.failures-before-lockout=1000");

    String cookie = sessionCookieHeader(guest());

    ResponseEntity<String> first = getWithCookie("/api/auth/session", cookie);
    assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(first.getHeaders().getFirst("X-RateLimit-Limit")).isEqualTo("2");
    assertThat(first.getHeaders().getFirst("X-RateLimit-Remaining")).isEqualTo("1");

    ResponseEntity<String> second = getWithCookie("/api/auth/session", cookie);
    assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(second.getHeaders().getFirst("X-RateLimit-Remaining")).isEqualTo("0");

    ResponseEntity<String> third = getWithCookie("/api/auth/session", cookie);
    assertThat(third.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    assertThat(third.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isNotNull();
  }

  @Test
  void tier4EnforcesThePublicReadCapWithoutAuthentication() {
    bootApplication(
        "--turnbase.rate-limit.public.capacity=2",
        "--turnbase.rate-limit.public.window=1h",
        "--turnbase.rate-limit.minting.guest.capacity=1000",
        "--turnbase.rate-limit.minting.registration.capacity=1000",
        "--turnbase.rate-limit.auth.failures-before-lockout=1000");

    String username = uniqueUsername();
    register(username, uniqueEmail(), "correct horse battery", "correct horse battery");

    assertThat(get("/api/users/" + username).getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(get("/api/users/" + username).getStatusCode()).isEqualTo(HttpStatus.OK);

    ResponseEntity<String> third = get("/api/users/" + username);
    assertThat(third.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
  }

  @Test
  void rateLimitingCanBeDisabledOnlyUnderTestE2eOrLoadtestProfile() {
    bootApplication(
        "--spring.profiles.active=test",
        "--turnbase.rate-limit.enabled=false",
        "--turnbase.rate-limit.minting.guest.capacity=1",
        "--turnbase.rate-limit.minting.guest.window=1h");

    for (int i = 0; i < 5; i++) {
      assertThat(guest().getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }
    stopApplication();

    bootApplication(
        "--spring.profiles.active=loadtest",
        "--turnbase.rate-limit.enabled=false",
        "--turnbase.rate-limit.minting.guest.capacity=1",
        "--turnbase.rate-limit.minting.guest.window=1h");
    for (int i = 0; i < 5; i++) {
      assertThat(guest().getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }
    stopApplication();

    assertThatThrownBy(
            () ->
                bootApplication(
                    "--spring.profiles.active=local", "--turnbase.rate-limit.switches.api=false"))
        .hasStackTraceContaining("turnbase.rate-limit.switches.api")
        .hasStackTraceContaining("only honoured under the test, e2e or loadtest profile");
  }

  @Test
  void disabledApiTierAdmitsMoreThanItsCapacity() {
    bootApplication(
        "--spring.profiles.active=test",
        "--turnbase.rate-limit.switches.api=false",
        "--turnbase.rate-limit.api.capacity=2",
        "--turnbase.rate-limit.api.window=1h",
        "--turnbase.rate-limit.minting.guest.capacity=1000");

    String cookie = sessionCookieHeader(guest());

    for (int i = 0; i < 6; i++) {
      ResponseEntity<String> response = getWithCookie("/api/auth/session", cookie);
      assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
      assertThat(response.getHeaders().getFirst("X-RateLimit-Limit")).isNull();
    }
    assertThat(redisKeyCount("*rate:api:*")).isZero();
  }

  @Test
  void disabledUploadTierDoesNotFallThroughToTheApiBucket() {
    bootApplication(
        "--spring.profiles.active=test",
        "--turnbase.rate-limit.switches.upload=false",
        "--turnbase.rate-limit.api.capacity=2",
        "--turnbase.rate-limit.api.window=1h",
        "--turnbase.rate-limit.upload.capacity=1",
        "--turnbase.rate-limit.upload.window=1h",
        "--turnbase.rate-limit.minting.guest.capacity=1000");

    String cookie = sessionCookieHeader(guest());

    // The route may answer 4xx for other reasons, but never 429, and neither bucket is touched.
    for (int i = 0; i < 6; i++) {
      ResponseEntity<String> response =
          postWithCookie("/api/games/some-game/versions", cookie, "{}");
      assertThat(response.getStatusCode()).isNotEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }
    assertThat(redisKeyCount("*rate:upload:*")).isZero();
    assertThat(redisKeyCount("*rate:api:*")).isZero();
  }

  @Test
  void disabledPublicTierAdmitsMoreThanItsCapacity() {
    bootApplication(
        "--spring.profiles.active=test",
        "--turnbase.rate-limit.switches.public=false",
        "--turnbase.rate-limit.public.capacity=2",
        "--turnbase.rate-limit.public.window=1h",
        "--turnbase.rate-limit.minting.registration.capacity=1000",
        "--turnbase.rate-limit.auth.failures-before-lockout=1000");

    String username = uniqueUsername();
    register(username, uniqueEmail(), "correct horse battery", "correct horse battery");

    for (int i = 0; i < 6; i++) {
      assertThat(get("/api/users/" + username).getStatusCode()).isEqualTo(HttpStatus.OK);
    }
    assertThat(redisKeyCount("*rate:public:*")).isZero();
  }

  @Test
  void disabledAuthLockoutNeverLocksOutRepeatedBadLogins() {
    bootApplication(
        "--spring.profiles.active=test",
        "--turnbase.rate-limit.switches.auth-lockout=false",
        "--turnbase.rate-limit.auth.failures-before-lockout=2",
        "--turnbase.rate-limit.auth.initial-lockout=1h",
        "--turnbase.rate-limit.minting.registration.capacity=1000");

    String username = uniqueUsername();
    register(username, uniqueEmail(), "correct horse battery", "correct horse battery");

    for (int i = 0; i < 6; i++) {
      assertThat(login(username, "wrong password").getStatusCode())
          .isEqualTo(HttpStatus.UNAUTHORIZED);
    }
    assertThat(login(username, "correct horse battery").getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(redisKeyCount("rate:auth:*")).isZero();
  }

  @Test
  void disabledGuestMintingDoesNotAffectRegistrationMinting() {
    bootApplication(
        "--spring.profiles.active=test",
        "--turnbase.rate-limit.switches.guest-minting=false",
        "--turnbase.rate-limit.minting.guest.capacity=1",
        "--turnbase.rate-limit.minting.guest.window=1h",
        "--turnbase.rate-limit.minting.registration.capacity=1",
        "--turnbase.rate-limit.minting.registration.window=1h");

    for (int i = 0; i < 4; i++) {
      assertThat(guest().getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }
    assertThat(
            register(
                    uniqueUsername(),
                    uniqueEmail(),
                    "correct horse battery",
                    "correct horse battery")
                .getStatusCode())
        .isEqualTo(HttpStatus.CREATED);
    assertThat(
            register(
                    uniqueUsername(),
                    uniqueEmail(),
                    "correct horse battery",
                    "correct horse battery")
                .getStatusCode())
        .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
  }

  @Test
  void disabledRegistrationMintingDoesNotAffectGuestMinting() {
    bootApplication(
        "--spring.profiles.active=test",
        "--turnbase.rate-limit.switches.registration-minting=false",
        "--turnbase.rate-limit.minting.guest.capacity=1",
        "--turnbase.rate-limit.minting.guest.window=1h",
        "--turnbase.rate-limit.minting.registration.capacity=1",
        "--turnbase.rate-limit.minting.registration.window=1h",
        "--turnbase.rate-limit.auth.failures-before-lockout=1000");

    for (int i = 0; i < 4; i++) {
      assertThat(
              register(
                      uniqueUsername(),
                      uniqueEmail(),
                      "correct horse battery",
                      "correct horse battery")
                  .getStatusCode())
          .isEqualTo(HttpStatus.CREATED);
    }
    assertThat(guest().getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(guest().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
  }

  // ---- boot and HTTP helpers ----

  private void bootApplication(String... extraArgs) {
    List<String> args =
        new ArrayList<>(
            List.of(
                "--server.port=0",
                "--management.server.port=0",
                "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "--spring.datasource.username=" + POSTGRES.getUsername(),
                "--spring.datasource.password=" + POSTGRES.getPassword(),
                "--spring.data.redis.url=" + redisUrl(),
                "--turnbase.artifact-store.endpoint=" + MINIO.getS3URL(),
                "--turnbase.artifact-store.bucket=" + ARTIFACT_BUCKET,
                "--turnbase.artifact-store.access-key=" + MINIO.getUserName(),
                "--turnbase.artifact-store.secret-key=" + MINIO.getPassword(),
                "--turnbase.admin.username=admin",
                "--turnbase.admin.password=admin"));
    args.addAll(List.of(extraArgs));
    context = SpringApplication.run(TurnBaseApplication.class, args.toArray(new String[0]));
    int port =
        Integer.parseInt(
            Objects.requireNonNull(context).getEnvironment().getProperty("local.server.port"));
    baseUrl = "http://127.0.0.1:" + port;
  }

  private ResponseEntity<String> guest() {
    return post("/api/auth/guest", null);
  }

  private ResponseEntity<String> register(
      String username, String email, String password, String confirmPassword) {
    return post(
        "/api/auth/register",
        """
        {"username":"%s","email":"%s","password":"%s","confirmPassword":"%s"}
        """
            .formatted(username, email, password, confirmPassword));
  }

  private ResponseEntity<String> login(String usernameOrEmail, String password) {
    return post(
        "/api/auth/login",
        """
        {"usernameOrEmail":"%s","password":"%s"}
        """
            .formatted(usernameOrEmail, password));
  }

  private int redisKeyCount(String pattern) {
    try {
      String out = REDIS.execInContainer("redis-cli", "--scan", "--pattern", pattern).getStdout();
      return (int) out.lines().filter(line -> !line.isBlank()).count();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private ResponseEntity<String> loginFrom(String forgedAddress, String user, String password) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.set("X-Forwarded-For", forgedAddress);
    return restTemplate.exchange(
        baseUrl + "/api/auth/login",
        HttpMethod.POST,
        new HttpEntity<>(
            """
            {"usernameOrEmail":"%s","password":"%s"}
            """
                .formatted(user, password),
            headers),
        String.class);
  }

  private ResponseEntity<String> post(String path, @Nullable String body) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    HttpEntity<String> request = new HttpEntity<>(body, headers);
    return restTemplate.exchange(baseUrl + path, HttpMethod.POST, request, String.class);
  }

  private ResponseEntity<String> postWithCookie(String path, String cookie, @Nullable String body) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.add(HttpHeaders.COOKIE, cookie);
    return restTemplate.exchange(
        baseUrl + path, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
  }

  private ResponseEntity<String> deleteWithCookie(String path, String cookie) {
    HttpHeaders headers = new HttpHeaders();
    headers.add(HttpHeaders.COOKIE, cookie);
    return restTemplate.exchange(
        baseUrl + path, HttpMethod.DELETE, new HttpEntity<>(headers), String.class);
  }

  private ResponseEntity<String> get(String path) {
    return restTemplate.exchange(baseUrl + path, HttpMethod.GET, HttpEntity.EMPTY, String.class);
  }

  private ResponseEntity<String> getWithCookie(String path, String cookie) {
    HttpHeaders headers = new HttpHeaders();
    headers.add(HttpHeaders.COOKIE, cookie);
    HttpEntity<Void> request = new HttpEntity<>(headers);
    return restTemplate.exchange(baseUrl + path, HttpMethod.GET, request, String.class);
  }

  private String sessionCookieHeader(ResponseEntity<String> response) {
    List<String> setCookies =
        Objects.requireNonNull(response.getHeaders().get(HttpHeaders.SET_COOKIE));
    return setCookies.stream()
        .filter(c -> c.startsWith("turnbase_session="))
        .findFirst()
        .orElseThrow()
        .split(";", 2)[0];
  }

  private static String uniqueUsername() {
    return "user" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
  }

  private static String uniqueEmail() {
    return "user-" + UUID.randomUUID() + "@example.com";
  }

  private static final class NoOpResponseErrorHandler implements ResponseErrorHandler {
    @Override
    public boolean hasError(ClientHttpResponse response) {
      return false;
    }

    @Override
    public void handleError(
        java.net.URI url,
        org.springframework.http.HttpMethod method,
        org.springframework.http.client.ClientHttpResponse response) {
      // No-op: 4xx/5xx responses are asserted on directly rather than thrown as exceptions.
    }
  }
}
