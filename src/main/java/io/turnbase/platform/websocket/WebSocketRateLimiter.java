package io.turnbase.platform.websocket;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.Refill;
import io.turnbase.platform.common.AuthenticatedUser;
import io.turnbase.platform.common.RateLimitMetrics;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.stereotype.Component;

/**
 * {@code rate-limiting.md} § Tier 6: pod-local {@code Bucket4j} {@code LocalBucket}s, keyed by
 * principal, classifying every inbound frame by destination from STOMP headers alone. Deliberately
 * not backed by Redis — room affinity (ADR-0002) already pins a user's whole frame stream for a
 * room to one pod, so a pod-local bucket is exact, and a Redis round trip on the hottest inbound
 * path buys nothing (`rate-limiting.md` § Two enforcement domains). The reconnect lockout (penalty
 * ladder step 3) is the one piece that *is* distributed, because the next {@code CONNECT} may land
 * on a different pod.
 */
@Component
class WebSocketRateLimiter {

  private static final String LOCKOUT_UNTIL_PREFIX = "rate:ws-lockout:until:";
  private static final String LOCKOUT_OFFENSES_PREFIX = "rate:ws-lockout:offenses:";
  private static final Duration OFFENSE_DECAY = Duration.ofHours(1);
  private static final long MAX_DOUBLINGS = 40;
  private static final Duration NOTIFY_THROTTLE = Duration.ofSeconds(1);

  private final WebSocketRateLimitProperties properties;
  private final StringRedisTemplate stringRedisTemplate;
  private final RateLimitMetrics metrics;

  private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
  private final Map<String, Deque<Instant>> violations = new ConcurrentHashMap<>();
  private final Map<String, Instant> lastNotifiedAt = new ConcurrentHashMap<>();

  WebSocketRateLimiter(
      WebSocketRateLimitProperties properties,
      StringRedisTemplate stringRedisTemplate,
      RateLimitMetrics metrics) {
    this.properties = properties;
    this.stringRedisTemplate = stringRedisTemplate;
    this.metrics = metrics;
  }

  boolean isLockedOut(UUID userId) {
    String lockedUntil =
        metrics.record(() -> stringRedisTemplate.opsForValue().get(LOCKOUT_UNTIL_PREFIX + userId));
    if (lockedUntil == null) {
      return false;
    }
    return Long.parseLong(lockedUntil) > System.currentTimeMillis();
  }

  /** Every frame after {@code CONNECT} — {@code websocket.md}'s own scoping of Tier 6. */
  FrameDecision checkFrame(
      AuthenticatedUser user,
      String stompSessionId,
      StompCommand command,
      @Nullable String destination) {
    ConsumptionProbe connectionProbe =
        consume(user.userId(), "connection", properties.connection());
    if (!connectionProbe.isConsumed()) {
      return onViolation(user, stompSessionId, "connection", connectionProbe);
    }
    String bucketName = classify(command, destination);
    if (bucketName == null) {
      return new FrameDecision.Allow();
    }
    ConsumptionProbe classProbe = consume(user.userId(), bucketName, bucketConfig(bucketName));
    if (!classProbe.isConsumed()) {
      return onViolation(user, stompSessionId, bucketName, classProbe);
    }
    return new FrameDecision.Allow();
  }

  private @Nullable String classify(StompCommand command, @Nullable String destination) {
    if (command == StompCommand.SUBSCRIBE) {
      return "subscribe";
    }
    if (command != StompCommand.SEND || destination == null) {
      return null;
    }
    if (destination.startsWith("/app/room.") && destination.endsWith(".chat")) {
      return "chat";
    }
    if (destination.startsWith("/app/room.")
        && (destination.endsWith(".member") || destination.endsWith(".host"))) {
      return "command";
    }
    if (destination.startsWith("/app/game.") && destination.endsWith(".sync")) {
      return "snapshot";
    }
    if (destination.startsWith("/app/game.") && destination.endsWith(".action")) {
      return "action";
    }
    return null;
  }

  private WebSocketRateLimitProperties.Bucket bucketConfig(String bucketName) {
    return switch (bucketName) {
      case "subscribe" -> properties.subscribe();
      case "chat" -> properties.chat();
      case "command" -> properties.command();
      case "snapshot" -> properties.snapshot();
      case "action" -> properties.action();
      default -> throw new IllegalArgumentException("unknown Tier 6 bucket " + bucketName);
    };
  }

  private ConsumptionProbe consume(
      UUID userId, String bucketName, WebSocketRateLimitProperties.Bucket config) {
    Bucket bucket = buckets.computeIfAbsent(userId + ":" + bucketName, key -> newBucket(config));
    return bucket.tryConsumeAndReturnRemaining(1);
  }

  private static Bucket newBucket(WebSocketRateLimitProperties.Bucket config) {
    return Bucket.builder()
        .addLimit(
            Bandwidth.classic(
                config.capacity(), Refill.greedy(config.refillTokens(), config.refillPeriod())))
        .build();
  }

  /**
   * Ladder step 1 (drop, throttled notice) unless this session has now exceeded {@code
   * violationsBeforeClose} within {@code violationWindow} — then step 2/3: force-close and write
   * the doubling reconnect lockout ({@code rate-limiting.md} § The penalty ladder).
   */
  private FrameDecision onViolation(
      AuthenticatedUser user, String stompSessionId, String bucketName, ConsumptionProbe probe) {
    long retryAfterMs = Math.ceilDiv(probe.getNanosToWaitForRefill(), 1_000_000L);
    if (recordViolationAndCheckSustained(stompSessionId)) {
      writeLockout(user.userId());
      return new FrameDecision.ForceClose("RATE_LIMIT_ABUSE");
    }
    boolean notify = shouldNotify(stompSessionId, bucketName);
    return new FrameDecision.Drop(bucketName, retryAfterMs, notify);
  }

  private boolean recordViolationAndCheckSustained(String stompSessionId) {
    Deque<Instant> timestamps =
        violations.computeIfAbsent(stompSessionId, key -> new ArrayDeque<>());
    Instant now = Instant.now();
    Instant windowStart = now.minus(properties.violationWindow());
    synchronized (timestamps) {
      timestamps.addLast(now);
      while (true) {
        @Nullable Instant oldest = timestamps.peekFirst();
        if (oldest == null || !oldest.isBefore(windowStart)) {
          break;
        }
        timestamps.removeFirst();
      }
      return timestamps.size() > properties.violationsBeforeClose();
    }
  }

  private boolean shouldNotify(String stompSessionId, String bucketName) {
    String key = stompSessionId + ":" + bucketName;
    Instant now = Instant.now();
    Instant last = lastNotifiedAt.get(key);
    if (last != null && last.plus(NOTIFY_THROTTLE).isAfter(now)) {
      return false;
    }
    lastNotifiedAt.put(key, now);
    return true;
  }

  private void writeLockout(UUID userId) {
    String offensesKey = LOCKOUT_OFFENSES_PREFIX + userId;
    Long offenses = metrics.record(() -> stringRedisTemplate.opsForValue().increment(offensesKey));
    metrics.record(
        () -> {
          stringRedisTemplate.expire(offensesKey, OFFENSE_DECAY);
          return null;
        });
    long doublings = Math.min((offenses == null ? 1 : offenses) - 1, MAX_DOUBLINGS);
    Duration lockout = properties.lockout().initial().multipliedBy(1L << doublings);
    if (lockout.compareTo(properties.lockout().max()) > 0) {
      lockout = properties.lockout().max();
    }
    long lockedUntil = System.currentTimeMillis() + lockout.toMillis();
    Duration finalLockout = lockout;
    metrics.record(
        () -> {
          stringRedisTemplate
              .opsForValue()
              .set(LOCKOUT_UNTIL_PREFIX + userId, String.valueOf(lockedUntil), finalLockout);
          return null;
        });
  }

  sealed interface FrameDecision {
    record Allow() implements FrameDecision {}

    record Drop(String bucket, long retryAfterMs, boolean shouldNotify) implements FrameDecision {}

    record ForceClose(String code) implements FrameDecision {}
  }
}
