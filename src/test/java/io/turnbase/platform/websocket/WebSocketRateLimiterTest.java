package io.turnbase.platform.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.benmanes.caffeine.cache.Ticker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.turnbase.platform.common.AuthenticatedUser;
import io.turnbase.platform.common.RateLimitMetrics;
import io.turnbase.platform.common.RateLimitProperties;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.messaging.simp.stomp.StompCommand;

/**
 * {@code rate-limiting.md} § Tier 6: pod-local buckets classified from headers alone, the
 * drop-then- throttled-notice ladder, and the sustained-violation force-close that writes the
 * reconnect lockout.
 */
class WebSocketRateLimiterTest {

  private final StringRedisTemplate stringRedisTemplate = mock(StringRedisTemplate.class);

  @SuppressWarnings("unchecked")
  private final ValueOperations<String, String> valueOperations = mock(ValueOperations.class);

  private final RateLimitMetrics metrics = new RateLimitMetrics(new SimpleMeterRegistry());
  private final RateLimitProperties masterOn = master(true);
  private final AuthenticatedUser user =
      new AuthenticatedUser(UUID.randomUUID(), UUID.randomUUID());

  {
    when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
  }

  @Test
  void firstFrameUnderEveryBudgetIsAllowed() {
    WebSocketRateLimiter limiter = newLimiter(bucket(10, 10, Duration.ofSeconds(1)), 20);

    var decision = limiter.checkFrame(user, "s1", StompCommand.SUBSCRIBE, "/topic/room.x");

    assertThat(decision).isInstanceOf(WebSocketRateLimiter.FrameDecision.Allow.class);
  }

  @Test
  void unsubscribeFramesDoNotConsumeTheSubscribeBudget() {
    // Tier 6 charges "any SUBSCRIBE frame" (rate-limiting.md): tearing a game view down must not
    // starve the next one's subscriptions.
    var roomy = bucket(100, 100, Duration.ofSeconds(1));
    var subscribe = bucket(1, 1, Duration.ofHours(1));
    var properties =
        new WebSocketRateLimitProperties(
            roomy,
            roomy,
            roomy,
            roomy,
            roomy,
            subscribe,
            4,
            20,
            Duration.ofSeconds(10),
            new WebSocketRateLimitProperties.Lockout(
                Duration.ofSeconds(30), Duration.ofMinutes(15)),
            WebSocketRateLimitProperties.Switches.allEnabled());
    var limiter = new WebSocketRateLimiter(masterOn, properties, stringRedisTemplate, metrics);

    for (int i = 0; i < 5; i++) {
      var decision = limiter.checkFrame(user, "s1", StompCommand.UNSUBSCRIBE, null);
      assertThat(decision).isInstanceOf(WebSocketRateLimiter.FrameDecision.Allow.class);
    }
    var first = limiter.checkFrame(user, "s1", StompCommand.SUBSCRIBE, "/topic/room.x");
    assertThat(first).isInstanceOf(WebSocketRateLimiter.FrameDecision.Allow.class);
  }

  @Test
  void exhaustingTheConnectionBucketDropsAndNotifiesOnceThenThrottles() {
    WebSocketRateLimiter limiter = newLimiter(bucket(1, 1, Duration.ofHours(1)), 20);
    limiter.checkFrame(user, "s1", StompCommand.SEND, "/app/room.x.chat");

    var second = limiter.checkFrame(user, "s1", StompCommand.SEND, "/app/room.x.chat");
    var third = limiter.checkFrame(user, "s1", StompCommand.SEND, "/app/room.x.chat");

    assertThat(second).isInstanceOf(WebSocketRateLimiter.FrameDecision.Drop.class);
    assertThat(((WebSocketRateLimiter.FrameDecision.Drop) second).shouldNotify()).isTrue();
    assertThat(third).isInstanceOf(WebSocketRateLimiter.FrameDecision.Drop.class);
    assertThat(((WebSocketRateLimiter.FrameDecision.Drop) third).shouldNotify()).isFalse();
  }

  @Test
  void sustainedViolationsForceCloseAndWriteTheReconnectLockout() {
    // connection capacity 1: every frame after the first violates it, so 3 violations trip a
    // violationsBeforeClose of 2.
    WebSocketRateLimiter limiter = newLimiter(bucket(1, 1, Duration.ofHours(1)), 2);
    when(valueOperations.increment(anyString())).thenReturn(1L);
    limiter.checkFrame(user, "s1", StompCommand.SEND, "/app/room.x.chat");
    limiter.checkFrame(user, "s1", StompCommand.SEND, "/app/room.x.chat");
    limiter.checkFrame(user, "s1", StompCommand.SEND, "/app/room.x.chat");

    var fourth = limiter.checkFrame(user, "s1", StompCommand.SEND, "/app/room.x.chat");

    assertThat(fourth).isInstanceOf(WebSocketRateLimiter.FrameDecision.ForceClose.class);
    assertThat(((WebSocketRateLimiter.FrameDecision.ForceClose) fourth).code())
        .isEqualTo("RATE_LIMIT_ABUSE");
    verify(valueOperations).set(anyString(), anyString(), any(Duration.class));
  }

  @Test
  void isLockedOutReflectsAFutureUnlockTimeOnly() {
    WebSocketRateLimiter limiter = newLimiter(bucket(10, 10, Duration.ofSeconds(1)), 20);
    when(valueOperations.get("rate:ws-lockout:until:" + user.userId()))
        .thenReturn(String.valueOf(System.currentTimeMillis() + 60_000));

    assertThat(limiter.isLockedOut(user.userId())).isTrue();

    when(valueOperations.get("rate:ws-lockout:until:" + user.userId()))
        .thenReturn(String.valueOf(System.currentTimeMillis() - 60_000));

    assertThat(limiter.isLockedOut(user.userId())).isFalse();
  }

  private WebSocketRateLimiter newLimiter(
      WebSocketRateLimitProperties.Bucket everyBucket, int violationsBeforeClose) {
    return newLimiter(everyBucket, violationsBeforeClose, Ticker.systemTicker());
  }

  private WebSocketRateLimiter newLimiter(
      WebSocketRateLimitProperties.Bucket everyBucket, int violationsBeforeClose, Ticker ticker) {
    return newLimiter(
        masterOn,
        everyBucket,
        violationsBeforeClose,
        WebSocketRateLimitProperties.Switches.allEnabled(),
        ticker);
  }

  private WebSocketRateLimiter newLimiter(
      RateLimitProperties master,
      WebSocketRateLimitProperties.Bucket everyBucket,
      int violationsBeforeClose,
      WebSocketRateLimitProperties.Switches switches,
      Ticker ticker) {
    WebSocketRateLimitProperties properties =
        new WebSocketRateLimitProperties(
            everyBucket,
            everyBucket,
            everyBucket,
            everyBucket,
            everyBucket,
            everyBucket,
            4,
            violationsBeforeClose,
            Duration.ofSeconds(10),
            new WebSocketRateLimitProperties.Lockout(
                Duration.ofSeconds(30), Duration.ofMinutes(15)),
            switches);
    return new WebSocketRateLimiter(master, properties, stringRedisTemplate, metrics, ticker);
  }

  private static RateLimitProperties master(boolean enabled) {
    return new RateLimitProperties(
        enabled,
        new RateLimitProperties.Auth(5, Duration.ofSeconds(30)),
        new RateLimitProperties.Bucket(100, Duration.ofSeconds(10)),
        new RateLimitProperties.Bucket(10, Duration.ofHours(1)),
        new RateLimitProperties.Bucket(200, Duration.ofMinutes(1)),
        new RateLimitProperties.Minting(
            new RateLimitProperties.Bucket(5, Duration.ofHours(1)),
            new RateLimitProperties.Bucket(3, Duration.ofHours(1))),
        RateLimitProperties.Switches.allEnabled());
  }

  /** All-enabled switches with the named ones turned off. */
  private static WebSocketRateLimitProperties.Switches without(String... names) {
    var off = java.util.Set.of(names);
    return new WebSocketRateLimitProperties.Switches(
        !off.contains("connection"),
        !off.contains("snapshot"),
        !off.contains("action"),
        !off.contains("chat"),
        !off.contains("command"),
        !off.contains("subscribe"),
        !off.contains("violationLockout"),
        !off.contains("maxConnectionsPerUser"));
  }

  private static WebSocketRateLimitProperties.Bucket bucket(
      int capacity, int refillTokens, Duration refillPeriod) {
    return new WebSocketRateLimitProperties.Bucket(capacity, refillTokens, refillPeriod);
  }

  @Test
  void disabledMasterSwitchAllowsEveryFrame() {
    var limiter =
        newLimiter(
            master(false),
            bucket(1, 1, Duration.ofHours(1)),
            1,
            WebSocketRateLimitProperties.Switches.allEnabled(),
            Ticker.systemTicker());
    when(valueOperations.get(anyString())).thenReturn(String.valueOf(Long.MAX_VALUE));

    for (int i = 0; i < 10; i++) {
      var decision = limiter.checkFrame(user, "s1", StompCommand.SEND, "/app/room.x.chat");
      assertThat(decision).isInstanceOf(WebSocketRateLimiter.FrameDecision.Allow.class);
    }
    assertThat(limiter.isLockedOut(user.userId())).isFalse();
    assertThat(limiter.connectionCapEnforced()).isFalse();
  }

  @Test
  void disabledConnectionBucketAllowsFramesThatWouldExhaustIt() {
    var limiter =
        newLimiter(
            masterOn,
            bucket(1, 1, Duration.ofHours(1)),
            20,
            without("connection"),
            Ticker.systemTicker());

    // Unclassified frames touch only the connection bucket.
    for (int i = 0; i < 5; i++) {
      var decision = limiter.checkFrame(user, "s1", StompCommand.SEND, "/app/unclassified");
      assertThat(decision).isInstanceOf(WebSocketRateLimiter.FrameDecision.Allow.class);
    }
  }

  @Test
  void disabledClassBucketAllowsItsFramesButNotOthers() {
    var limiter =
        newLimiter(
            masterOn,
            bucket(1, 1, Duration.ofHours(1)),
            20,
            without("chat"),
            Ticker.systemTicker());
    // The connection bucket (capacity 1) would stop the second frame, so give it room first.
    var roomy = bucket(100, 100, Duration.ofSeconds(1));
    var props =
        new WebSocketRateLimitProperties(
            roomy,
            roomy,
            roomy,
            bucket(1, 1, Duration.ofHours(1)),
            bucket(1, 1, Duration.ofHours(1)),
            roomy,
            4,
            20,
            Duration.ofSeconds(10),
            new WebSocketRateLimitProperties.Lockout(
                Duration.ofSeconds(30), Duration.ofMinutes(15)),
            without("chat"));
    limiter = new WebSocketRateLimiter(masterOn, props, stringRedisTemplate, metrics);

    for (int i = 0; i < 5; i++) {
      var chat = limiter.checkFrame(user, "s1", StompCommand.SEND, "/app/room.x.chat");
      assertThat(chat).isInstanceOf(WebSocketRateLimiter.FrameDecision.Allow.class);
    }
    limiter.checkFrame(user, "s1", StompCommand.SEND, "/app/room.x.member");
    var command = limiter.checkFrame(user, "s1", StompCommand.SEND, "/app/room.x.member");
    assertThat(command).isInstanceOf(WebSocketRateLimiter.FrameDecision.Drop.class);
  }

  @Test
  void disabledViolationLockoutDropsButNeverForceClosesOrWritesALockout() {
    var limiter =
        newLimiter(
            masterOn,
            bucket(1, 1, Duration.ofHours(1)),
            1,
            without("violationLockout"),
            Ticker.systemTicker());

    for (int i = 0; i < 10; i++) {
      var decision = limiter.checkFrame(user, "s1", StompCommand.SEND, "/app/room.x.chat");
      if (i > 0) {
        assertThat(decision).isInstanceOf(WebSocketRateLimiter.FrameDecision.Drop.class);
      }
    }
    verify(valueOperations, never()).set(anyString(), anyString(), any(Duration.class));
    verify(valueOperations, never()).increment(anyString());
  }

  @Test
  void disabledViolationLockoutMakesIsLockedOutFalseDespiteAStoredKey() {
    var limiter =
        newLimiter(
            masterOn,
            bucket(10, 10, Duration.ofSeconds(1)),
            20,
            without("violationLockout"),
            Ticker.systemTicker());
    when(valueOperations.get("rate:ws-lockout:until:" + user.userId()))
        .thenReturn(String.valueOf(System.currentTimeMillis() + 60_000));

    assertThat(limiter.isLockedOut(user.userId())).isFalse();
  }

  @Test
  void connectionCapIsEnforcedOnlyWhenMasterAndItsOwnSwitchAreOn() {
    var on =
        newLimiter(
            masterOn, bucket(1, 1, Duration.ofHours(1)), 20, without(), Ticker.systemTicker());
    var off =
        newLimiter(
            masterOn,
            bucket(1, 1, Duration.ofHours(1)),
            20,
            without("maxConnectionsPerUser"),
            Ticker.systemTicker());

    assertThat(on.connectionCapEnforced()).isTrue();
    assertThat(off.connectionCapEnforced()).isFalse();
  }

  @Test
  void idleKeysAreEvictedSoDistinctUserChurnDoesNotGrowTheMaps() {
    long[] nanos = {0};
    WebSocketRateLimiter limiter =
        newLimiter(bucket(1, 1, Duration.ofHours(1)), 1_000, () -> nanos[0]);
    for (int i = 0; i < 5_000; i++) {
      AuthenticatedUser churn = new AuthenticatedUser(UUID.randomUUID(), UUID.randomUUID());
      // Two frames per user: the second exceeds the 1-token bucket, so a violation is recorded.
      limiter.checkFrame(churn, "s" + i, StompCommand.SEND, "/app/room.x.chat");
      limiter.checkFrame(churn, "s" + i, StompCommand.SEND, "/app/room.x.chat");
    }
    assertThat(limiter.trackedKeys()).isGreaterThan(5_000);
    nanos[0] += Duration.ofHours(2).toNanos();
    assertThat(limiter.trackedKeys()).isZero();
  }
}
