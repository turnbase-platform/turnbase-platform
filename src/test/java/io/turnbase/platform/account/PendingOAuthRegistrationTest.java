package io.turnbase.platform.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.turnbase.platform.common.RateLimitMetrics;
import io.turnbase.platform.session.AuthProperties;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.databind.json.JsonMapper;

/** {@code oauth-rest-api.md} §2 — the Redis-backed pending-registration bridge. */
class PendingOAuthRegistrationTest {

  @SuppressWarnings("unchecked")
  private final ValueOperations<String, String> valueOps = mock(ValueOperations.class);

  private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
  private final JsonMapper objectMapper = JsonMapper.builder().build();
  private final RateLimitMetrics metrics = new RateLimitMetrics(new SimpleMeterRegistry());
  private final AuthProperties authProperties =
      new AuthProperties(
          Duration.ofHours(24),
          false,
          null,
          new AuthProperties.OAuth(Duration.ofMinutes(5), Duration.ofMinutes(10)));

  private final PendingOAuthRegistration pending =
      new PendingOAuthRegistration(redis, objectMapper, metrics, authProperties);

  {
    when(redis.opsForValue()).thenReturn(valueOps);
  }

  @Test
  void createWritesAJsonRecordWithTheConfiguredTtl() {
    String token = pending.create("sub-1", "alice@example.com");

    verify(valueOps).set(eq("oauth:pending:" + token), anyString(), eq(Duration.ofMinutes(10)));
  }

  @Test
  void findReturnsEmptyWhenTheKeyIsAbsent() {
    when(valueOps.get(anyString())).thenReturn(null);

    assertThat(pending.find("missing-token")).isEmpty();
  }

  @Test
  void findRoundTripsTheRecordJustWritten() {
    String[] captured = new String[1];
    org.mockito.Mockito.doAnswer(
            invocation -> {
              captured[0] = invocation.getArgument(1);
              return null;
            })
        .when(valueOps)
        .set(anyString(), anyString(), any(Duration.class));
    String token = pending.create("sub-1", "alice@example.com");
    when(valueOps.get("oauth:pending:" + token)).thenReturn(captured[0]);

    Optional<PendingOAuthRegistration.Record> found = pending.find(token);

    assertThat(found).isPresent();
    assertThat(found.get().providerSubject()).isEqualTo("sub-1");
    assertThat(found.get().email()).isEqualTo("alice@example.com");
    assertThat(found.get().collisionUserId()).isNull();
  }

  @Test
  void markCollisionPreservesTheRemainingTtlRatherThanResettingIt() {
    PendingOAuthRegistration.Record record =
        new PendingOAuthRegistration.Record("sub-1", "alice@example.com", null);
    UUID collisionUserId = UUID.randomUUID();
    when(redis.getExpire("oauth:pending:tok")).thenReturn(42L);

    pending.markCollision("tok", record, collisionUserId);

    verify(valueOps).set(eq("oauth:pending:tok"), anyString(), eq(Duration.ofSeconds(42)));
  }

  @Test
  void markCollisionFallsBackToTheFullTtlWhenTheKeyHasNoRemainingTime() {
    PendingOAuthRegistration.Record record =
        new PendingOAuthRegistration.Record("sub-1", "alice@example.com", null);
    when(redis.getExpire("oauth:pending:tok")).thenReturn(-2L);

    pending.markCollision("tok", record, UUID.randomUUID());

    verify(valueOps).set(eq("oauth:pending:tok"), anyString(), eq(Duration.ofMinutes(10)));
  }

  @Test
  void consumeDeletesTheKeyAndNothingElse() {
    pending.consume("tok");

    verify(redis, times(1)).delete("oauth:pending:tok");
    verify(valueOps, never()).set(anyString(), anyString(), any(Duration.class));
  }
}
