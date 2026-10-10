package io.turnbase.platform.account;

import io.turnbase.platform.common.RateLimitMetrics;
import io.turnbase.platform.session.AuthProperties;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code oauth-rest-api.md} §2 — the Redis-backed bridge between {@code /auth/callback} and the
 * three JSON endpoints that finish a first-time Google registration. Never a table: the record's
 * loss costs one repeat of the OAuth round trip, the same design as the guest-mint token this repo
 * already treats as disposable. {@code concern=application} ({@code conventions.md} §7) — the same
 * tag {@code common.RateLimitMetrics} already carries for {@code IMP-M1-02}'s Redis calls.
 */
@Component
class PendingOAuthRegistration {

  private static final String REDIS_KEY_PREFIX = "oauth:pending:";

  private final StringRedisTemplate redis;
  private final JsonMapper objectMapper;
  private final RateLimitMetrics metrics;
  private final Duration ttl;

  PendingOAuthRegistration(
      StringRedisTemplate redis,
      JsonMapper objectMapper,
      RateLimitMetrics metrics,
      AuthProperties authProperties) {
    this.redis = redis;
    this.objectMapper = objectMapper;
    this.metrics = metrics;
    this.ttl = authProperties.oauth().pendingRegistrationTtl();
  }

  /** §4 step 5 — a first-time Google login. Returns the token the cookie carries. */
  String create(String providerSubject, String email) {
    String token = UUID.randomUUID().toString();
    write(token, new Record(providerSubject, email, null), ttl);
    return token;
  }

  /** The cookie's {@code Max-Age} must match this — {@code oauth-rest-api.md} §2. */
  Duration ttl() {
    return ttl;
  }

  Optional<Record> find(String token) {
    String json = metrics.record(() -> redis.opsForValue().get(REDIS_KEY_PREFIX + token));
    if (json == null) {
      return Optional.empty();
    }
    return Optional.of(deserialize(json));
  }

  /**
   * §6 step 3 — marks the collision so {@code link-challenge} (§7) knows which account to
   * challenge. "Still under its original TTL — this does not extend it": the remaining time on the
   * existing key is read back and reapplied rather than resetting the window.
   */
  void markCollision(String token, Record existing, UUID collisionUserId) {
    Long remainingSeconds = metrics.record(() -> redis.getExpire(REDIS_KEY_PREFIX + token));
    Duration remaining =
        (remainingSeconds != null && remainingSeconds > 0)
            ? Duration.ofSeconds(remainingSeconds)
            : ttl;
    write(
        token,
        new Record(existing.providerSubject(), existing.email(), collisionUserId),
        remaining);
  }

  /** Consumed by a successful {@code complete-registration} or {@code link-challenge}. */
  void consume(String token) {
    metrics.record(() -> redis.delete(REDIS_KEY_PREFIX + token));
  }

  private void write(String token, Record record, Duration recordTtl) {
    String json = serialize(record);
    metrics.record(
        () -> {
          redis.opsForValue().set(REDIS_KEY_PREFIX + token, json, recordTtl);
          return null;
        });
  }

  private String serialize(Record record) {
    try {
      return objectMapper.writeValueAsString(record);
    } catch (JacksonException e) {
      throw new IllegalStateException("could not serialize an oauth pending record", e);
    }
  }

  private Record deserialize(String json) {
    try {
      return objectMapper.readValue(json, Record.class);
    } catch (JacksonException e) {
      throw new IllegalStateException("corrupt oauth pending record in redis", e);
    }
  }

  record Record(String providerSubject, String email, @Nullable UUID collisionUserId) {}
}
