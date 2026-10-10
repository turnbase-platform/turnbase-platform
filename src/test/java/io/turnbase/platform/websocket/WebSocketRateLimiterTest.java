package io.turnbase.platform.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.turnbase.platform.common.AuthenticatedUser;
import io.turnbase.platform.common.RateLimitMetrics;
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
                Duration.ofSeconds(30), Duration.ofMinutes(15)));
    var limiter = new WebSocketRateLimiter(properties, stringRedisTemplate, metrics);

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
                Duration.ofSeconds(30), Duration.ofMinutes(15)));
    return new WebSocketRateLimiter(properties, stringRedisTemplate, metrics);
  }

  private static WebSocketRateLimitProperties.Bucket bucket(
      int capacity, int refillTokens, Duration refillPeriod) {
    return new WebSocketRateLimitProperties.Bucket(capacity, refillTokens, refillPeriod);
  }
}
