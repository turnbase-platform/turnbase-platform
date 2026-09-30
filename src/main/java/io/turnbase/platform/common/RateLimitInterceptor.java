package io.turnbase.platform.common;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * {@code rate-limiting.md} Tiers 1-5, one {@code HandlerInterceptor} covering all of them — {@code
 * /api/auth/register} is governed by Tier 1 (IP, failure-counting) and Tier 5 (IP,
 * success-counting) simultaneously, via two independent Redis counters, so splitting the tiers
 * across separate interceptor classes would only duplicate the endpoint classification with no
 * benefit. Tier 6 (WebSocket frames, pod-local) is {@code IMP-M2-02}'s own mechanism.
 *
 * <p>Endpoint classification is exact-match against what exists today, the same convention {@code
 * session.SessionAuthenticationInterceptor} uses for NFR-SEC-006 — the item that adds a new Tier 1
 * endpoint or a Tier 3 endpoint (the upload version-create route, {@code IMP-M4-03}) extends the
 * matching set here. {@code IMP-M1-10} did exactly that for Tier 1 ({@code POST
 * /api/auth/oauth/link-challenge}, an auth-method-addition credential guess), Tier 4 ({@code GET
 * /auth/google}/{@code /auth/callback}, unauthenticated reads with no account-mutation consequence)
 * and Tier 5 ({@code POST /api/auth/oauth/complete-registration}'s success path — see {@code
 * oauth-rest-api.md} §6 for why the account-creation event is there and not at the callback).
 */
@Component
class RateLimitInterceptor implements HandlerInterceptor {

  private static final String USERS_PATH_PREFIX = "/api/users/";
  private static final String LOGIN_PATH = "/api/auth/login";
  private static final String REGISTER_PATH = "/api/auth/register";
  private static final String GUEST_PATH = "/api/auth/guest";
  private static final String PASSWORD_CHANGE_PATH = "/api/users/me/password";
  private static final String OAUTH_LINK_CHALLENGE_PATH = "/api/auth/oauth/link-challenge";
  private static final String OAUTH_COMPLETE_REGISTRATION_PATH =
      "/api/auth/oauth/complete-registration";
  private static final String AUTH_METHODS_PATH_PREFIX = "/api/users/me/auth-methods/";
  private static final Set<String> TIER1_PATHS =
      Set.of(
          LOGIN_PATH,
          REGISTER_PATH,
          PASSWORD_CHANGE_PATH,
          OAUTH_LINK_CHALLENGE_PATH,
          AUTH_METHODS_PATH_PREFIX + "password",
          AUTH_METHODS_PATH_PREFIX + "google");
  private static final Set<String> OAUTH_REDIRECT_PATHS =
      Set.of("/auth/google", "/auth/callback", "/api/auth/providers");
  private static final Pattern UPLOAD_VERSION_CREATE_PATH =
      Pattern.compile("^/api/games/[^/]+/versions$");

  private static final String AUTH_FAILURE_COUNT_PREFIX = "rate:auth:count:";
  private static final String AUTH_LOCKOUT_PREFIX = "rate:auth:lockout:";

  /**
   * No decay window is stated for Tier 1 (unlike Tier 6's explicit one hour) — this is memory
   * hygiene for the Redis key, generous enough never to interrupt an active escalation.
   */
  private static final Duration AUTH_FAILURE_COUNTER_TTL = Duration.ofHours(24);

  /**
   * Bounds the doubling shift so a very persistent attacker cannot overflow the lockout math; 2^40
   * lockout periods is centuries away from mattering as a policy distinction.
   */
  private static final long MAX_LOCKOUT_DOUBLINGS = 40;

  private static final String TIER5_BUCKET_ATTRIBUTE =
      RateLimitInterceptor.class.getName() + ".tier5Bucket";

  private final RateLimitProperties properties;
  private final ClientIpResolver ipResolver;
  private final ProxyManager<byte[]> proxyManager;
  private final StringRedisTemplate stringRedisTemplate;
  private final RateLimitMetrics metrics;

  RateLimitInterceptor(
      RateLimitProperties properties,
      SecurityProperties securityProperties,
      ProxyManager<byte[]> proxyManager,
      StringRedisTemplate stringRedisTemplate,
      RateLimitMetrics metrics) {
    this.properties = properties;
    this.ipResolver = new ClientIpResolver(securityProperties);
    this.proxyManager = proxyManager;
    this.stringRedisTemplate = stringRedisTemplate;
    this.metrics = metrics;
  }

  @Override
  public boolean preHandle(
      HttpServletRequest request, HttpServletResponse response, Object handler) {
    if (!properties.enabled()) {
      return true;
    }

    String method = request.getMethod();
    String path = pathOf(request);

    if (isTier1(method, path)) {
      enforceAuthFailureLockout(request);
    }
    if (isTier5(method, path)) {
      consumeMintingToken(request, path);
    }

    Classification classification = classify(request, method, path);
    if (classification != null) {
      consumeGeneralBucket(request, response, classification);
    }
    return true;
  }

  @Override
  public void afterCompletion(
      HttpServletRequest request,
      HttpServletResponse response,
      Object handler,
      @Nullable Exception ex) {
    if (!properties.enabled()) {
      return;
    }
    boolean failed = response.getStatus() >= 400;

    if (failed && isTier1(request.getMethod(), pathOf(request))) {
      recordAuthFailure(request);
    }

    Object attribute = request.getAttribute(TIER5_BUCKET_ATTRIBUTE);
    // Tier 5 counts successes only. Every minting endpoint's only success status is 201 (a row was
    // created); refund on anything else — a >=400 failure (validation error, duplicate
    // username/email) or, for complete-registration, a 200 CHALLENGE_REQUIRED (oauth-rest-api.md
    // §6 — no account created, so no mint happened) — so neither ever counts against the cap.
    if (response.getStatus() != HttpServletResponse.SC_CREATED
        && attribute instanceof Bucket mintingBucket) {
      metrics.record(
          () -> {
            mintingBucket.addTokens(1);
            return null;
          });
    }
  }

  // ---- Tier 1 — auth failures (IP, strict, doubling lockout) ----

  private void enforceAuthFailureLockout(HttpServletRequest request) {
    String lockKey = AUTH_LOCKOUT_PREFIX + ipResolver.resolve(request);
    String lockedUntil = metrics.record(() -> stringRedisTemplate.opsForValue().get(lockKey));
    if (lockedUntil == null) {
      return;
    }
    long remainingMillis = Long.parseLong(lockedUntil) - System.currentTimeMillis();
    if (remainingMillis > 0) {
      throw new RateLimitExceededException(
          "Too many failed attempts. Please wait before retrying.",
          Math.ceilDiv(remainingMillis, 1000L));
    }
  }

  private void recordAuthFailure(HttpServletRequest request) {
    String ip = ipResolver.resolve(request);
    String countKey = AUTH_FAILURE_COUNT_PREFIX + ip;
    Long failures = metrics.record(() -> stringRedisTemplate.opsForValue().increment(countKey));
    metrics.record(
        () -> {
          stringRedisTemplate.expire(countKey, AUTH_FAILURE_COUNTER_TTL);
          return null;
        });
    if (failures == null) {
      return;
    }
    int threshold = properties.auth().failuresBeforeLockout();
    if (failures < threshold) {
      return;
    }
    long doublings = Math.min(failures - threshold, MAX_LOCKOUT_DOUBLINGS);
    Duration lockout = properties.auth().initialLockout().multipliedBy(1L << doublings);
    String lockKey = AUTH_LOCKOUT_PREFIX + ip;
    long lockedUntil = System.currentTimeMillis() + lockout.toMillis();
    metrics.record(
        () -> {
          stringRedisTemplate.opsForValue().set(lockKey, String.valueOf(lockedUntil), lockout);
          return null;
        });
  }

  // ---- Tier 5 — account minting (IP, success-counting) ----

  private void consumeMintingToken(HttpServletRequest request, String path) {
    boolean registration = !GUEST_PATH.equals(path);
    RateLimitProperties.Bucket config =
        registration ? properties.minting().registration() : properties.minting().guest();
    String key =
        (registration ? "rate:mint:register:" : "rate:mint:guest:") + ipResolver.resolve(request);
    Bucket bucket = proxyManager.getProxy(bytesOf(key), bandwidthSupplier(config));
    ConsumptionProbe probe = metrics.record(() -> bucket.tryConsumeAndReturnRemaining(1));
    if (!probe.isConsumed()) {
      throw new RateLimitExceededException(
          "Too many accounts created from this address. Please wait before retrying.",
          retryAfterSeconds(probe));
    }
    // Only successes count (rate-limiting.md § Tier 5) — the token consumed here is refunded in
    // afterCompletion if the request ultimately fails.
    request.setAttribute(TIER5_BUCKET_ATTRIBUTE, bucket);
  }

  // ---- Tiers 2, 3, 4 — plain capacity/window buckets ----

  private @Nullable Classification classify(
      HttpServletRequest request, String method, String path) {
    if (isTier1(method, path) || isTier5(method, path)) {
      // Governed exclusively by their own tier above — Tier 2 is "not covered by another tier".
      return null;
    }
    if (isPublicRead(method, path) || isOAuthRedirect(method, path)) {
      return new Classification(
          "rate:public:" + ipResolver.resolve(request), properties.publicTier(), false);
    }
    AuthenticatedUser user = principalOf(request);
    if (user == null) {
      return null;
    }
    if (isUploadVersionCreate(method, path)) {
      return new Classification("rate:upload:" + user.userId(), properties.upload(), false);
    }
    return new Classification("rate:api:" + user.userId(), properties.api(), true);
  }

  private void consumeGeneralBucket(
      HttpServletRequest request, HttpServletResponse response, Classification classification) {
    Bucket bucket =
        proxyManager.getProxy(
            bytesOf(classification.redisKey()), bandwidthSupplier(classification.config()));
    ConsumptionProbe probe = metrics.record(() -> bucket.tryConsumeAndReturnRemaining(1));

    if (classification.emitHeaders()) {
      response.setHeader("X-RateLimit-Limit", String.valueOf(classification.config().capacity()));
      response.setHeader("X-RateLimit-Remaining", String.valueOf(probe.getRemainingTokens()));
      response.setHeader(
          "X-RateLimit-Reset",
          String.valueOf(Instant.now().plusNanos(probe.getNanosToWaitForReset()).getEpochSecond()));
    }
    if (!probe.isConsumed()) {
      throw new RateLimitExceededException(
          "Too many requests. Please wait before retrying.", retryAfterSeconds(probe));
    }
  }

  // ---- Shared helpers ----

  private static java.util.function.Supplier<BucketConfiguration> bandwidthSupplier(
      RateLimitProperties.Bucket config) {
    return () ->
        BucketConfiguration.builder()
            .addLimit(Bandwidth.simple(config.capacity(), config.window()))
            .build();
  }

  private static long retryAfterSeconds(ConsumptionProbe probe) {
    return Math.ceilDiv(probe.getNanosToWaitForRefill(), 1_000_000_000L);
  }

  private static byte[] bytesOf(String key) {
    return key.getBytes(StandardCharsets.UTF_8);
  }

  private static boolean isTier1(String method, String path) {
    if ("POST".equalsIgnoreCase(method)) {
      return TIER1_PATHS.contains(path);
    }
    // IMP-GATE-78: auth-method removal is a DELETE on a variable segment, so it is not in the set.
    return "DELETE".equalsIgnoreCase(method) && path.startsWith(AUTH_METHODS_PATH_PREFIX);
  }

  private static boolean isTier5(String method, String path) {
    return "POST".equalsIgnoreCase(method)
        && (GUEST_PATH.equals(path)
            || REGISTER_PATH.equals(path)
            || OAUTH_COMPLETE_REGISTRATION_PATH.equals(path));
  }

  private static boolean isUploadVersionCreate(String method, String path) {
    return "POST".equalsIgnoreCase(method) && UPLOAD_VERSION_CREATE_PATH.matcher(path).matches();
  }

  /**
   * {@code oauth-rest-api.md} §1/§3/§4 — Tier 4 (unauthenticated, lenient): none of these routes
   * ({@code /api/auth/providers} included, IMP-GATE-76) mutates an account, so the general-abuse
   * budget is the right shape, not a dedicated bucket.
   */
  private static boolean isOAuthRedirect(String method, String path) {
    return "GET".equalsIgnoreCase(method) && OAUTH_REDIRECT_PATHS.contains(path);
  }

  /**
   * The same public-read shape {@code session.SessionAuthenticationInterceptor} exempts from
   * authentication — {@code GET /api/users/{username}} and its {@code /friends} and {@code
   * /history} suffixes.
   */
  private static boolean isPublicRead(String method, String path) {
    if (!"GET".equalsIgnoreCase(method) || !path.startsWith(USERS_PATH_PREFIX)) {
      return false;
    }
    String rest = path.substring(USERS_PATH_PREFIX.length());
    if (rest.isEmpty() || "me".equals(rest) || rest.startsWith("me/")) {
      return false;
    }
    return !rest.contains("/")
        || rest.endsWith("/friends")
        || rest.endsWith("/history")
        || rest.endsWith("/statistics");
  }

  private static @Nullable AuthenticatedUser principalOf(HttpServletRequest request) {
    Principal principal = request.getUserPrincipal();
    return principal instanceof AuthenticatedUser authenticatedUser ? authenticatedUser : null;
  }

  private static String pathOf(HttpServletRequest request) {
    return request.getRequestURI().substring(request.getContextPath().length());
  }

  private record Classification(
      String redisKey, RateLimitProperties.Bucket config, boolean emitHeaders) {}
}
