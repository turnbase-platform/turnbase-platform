package io.turnbase.platform.websocket;

import java.time.Clock;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * {@code IMP-M18-08} / ADR-0020 §4: the per-user WebSocket cap ({@code rate-limiting.md} § Tier 6)
 * counted across every Platform instance. One sorted set per user, {@code ws:conns:{userId}}, whose
 * members are STOMP session ids and whose scores are lease expiry times: a connection renews its
 * lease while it is open ({@link #renew}), so an instance that dies takes its connections out of
 * the count when the leases lapse, and a user is never locked out by a crashed pod.
 *
 * <p>Redis being unreachable falls back to the instance's own count ({@link
 * WebSocketConnectionRegistry} still enforces it): the cap is an abuse control, not a correctness
 * property, and refusing every connection because Redis blinked would turn it into an outage.
 */
@Component
class ConnectionCapLedger {

  private static final Logger log = LoggerFactory.getLogger(ConnectionCapLedger.class);

  static final Duration LEASE = Duration.ofSeconds(90);

  private static final String KEY_PREFIX = "ws:conns:";

  /**
   * Drops lapsed leases, then admits {@code ARGV[3]} only while the set is under {@code ARGV[4]}.
   */
  private static final DefaultRedisScript<Long> ACQUIRE =
      new DefaultRedisScript<>(
          """
          redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', ARGV[1])
          if redis.call('ZSCORE', KEYS[1], ARGV[3]) == false
              and redis.call('ZCARD', KEYS[1]) >= tonumber(ARGV[4]) then
            return 0
          end
          redis.call('ZADD', KEYS[1], ARGV[2], ARGV[3])
          redis.call('PEXPIRE', KEYS[1], ARGV[5])
          return 1
          """,
          Long.class);

  private final StringRedisTemplate redis;
  private final Clock clock;

  @Autowired
  ConnectionCapLedger(StringRedisTemplate redis) {
    this(redis, Clock.systemUTC());
  }

  ConnectionCapLedger(StringRedisTemplate redis, Clock clock) {
    this.redis = redis;
    this.clock = clock;
  }

  /** True when {@code stompSessionId} fits under {@code max} for the user across the cluster. */
  boolean tryAcquire(UUID userId, String stompSessionId, int max) {
    long now = clock.millis();
    try {
      Long admitted =
          redis.execute(
              ACQUIRE,
              List.of(KEY_PREFIX + userId),
              Long.toString(now),
              Long.toString(now + LEASE.toMillis()),
              stompSessionId,
              Integer.toString(max),
              Long.toString(LEASE.toMillis() * 2));
      return admitted != null && admitted == 1L;
    } catch (RuntimeException e) {
      log.warn("Connection ledger unavailable, local cap only: {}", e.toString());
      return true;
    }
  }

  void release(UUID userId, String stompSessionId) {
    try {
      redis.opsForZSet().remove(KEY_PREFIX + userId, stompSessionId);
    } catch (RuntimeException e) {
      log.warn("Connection ledger release failed: {}", e.toString());
    }
  }

  /** Extends the lease of every connection in {@code open} (user id, STOMP session id pairs). */
  @SuppressWarnings("NullAway") // a pipelined callback must return null
  void renew(Collection<Lease> open) {
    if (open.isEmpty()) {
      return;
    }
    long expiry = clock.millis() + LEASE.toMillis();
    try {
      redis.executePipelined(
          (org.springframework.data.redis.core.RedisCallback<
                  @org.jspecify.annotations.Nullable Object>)
              connection -> {
                for (Lease lease : open) {
                  connection
                      .zSetCommands()
                      .zAdd(
                          (KEY_PREFIX + lease.userId())
                              .getBytes(java.nio.charset.StandardCharsets.UTF_8),
                          expiry,
                          lease.stompSessionId().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
                return null;
              });
    } catch (RuntimeException e) {
      log.warn("Connection ledger renewal failed: {}", e.toString());
    }
  }

  record Lease(UUID userId, String stompSessionId) {}
}
