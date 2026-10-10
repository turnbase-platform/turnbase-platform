package io.turnbase.platform.account;

import io.turnbase.platform.common.RateLimitMetrics;
import io.turnbase.platform.session.AuthProperties;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code oauth-rest-api.md} §7a — the Redis record {@code /auth/callback} leaves for the settings
 * page's confirming request. Same disposable-record design as {@link PendingOAuthRegistration},
 * keyed apart ({@code oauth:link:}) because the two records answer different questions: that one
 * says "a Google identity wants an account", this one says "this account's owner asked to add that
 * Google identity".
 */
@Component
class PendingGoogleLink {

  private static final String REDIS_KEY_PREFIX = "oauth:link:";

  private final StringRedisTemplate redis;
  private final JsonMapper objectMapper;
  private final RateLimitMetrics metrics;
  private final Duration ttl;

  PendingGoogleLink(
      StringRedisTemplate redis,
      JsonMapper objectMapper,
      RateLimitMetrics metrics,
      AuthProperties authProperties) {
    this.redis = redis;
    this.objectMapper = objectMapper;
    this.metrics = metrics;
    // No property of its own: the window is the same "user walked away from the tab" as §2's.
    this.ttl = authProperties.oauth().pendingRegistrationTtl();
  }

  /** Returns the token the {@code turnbase_oauth_link} cookie carries. */
  String create(UUID userId, String providerSubject) {
    String token = UUID.randomUUID().toString();
    String json = serialize(new Record(userId, providerSubject));
    metrics.record(
        () -> {
          redis.opsForValue().set(REDIS_KEY_PREFIX + token, json, ttl);
          return null;
        });
    return token;
  }

  /** The cookie's {@code Max-Age} must match this — {@code oauth-rest-api.md} §2. */
  Duration ttl() {
    return ttl;
  }

  Optional<Record> find(String token) {
    String json = metrics.record(() -> redis.opsForValue().get(REDIS_KEY_PREFIX + token));
    return json == null ? Optional.empty() : Optional.of(deserialize(json));
  }

  void consume(String token) {
    metrics.record(() -> redis.delete(REDIS_KEY_PREFIX + token));
  }

  private String serialize(Record record) {
    try {
      return objectMapper.writeValueAsString(record);
    } catch (JacksonException e) {
      throw new IllegalStateException("could not serialize an oauth link record", e);
    }
  }

  private Record deserialize(String json) {
    try {
      return objectMapper.readValue(json, Record.class);
    } catch (JacksonException e) {
      throw new IllegalStateException("corrupt oauth link record in redis", e);
    }
  }

  record Record(UUID userId, String providerSubject) {}
}
