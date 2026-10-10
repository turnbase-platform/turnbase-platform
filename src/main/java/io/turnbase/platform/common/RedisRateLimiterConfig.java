package io.turnbase.platform.common;

import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.redis.lettuce.Bucket4jLettuce;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import java.time.Duration;
import org.springframework.boot.data.redis.autoconfigure.DataRedisConnectionDetails;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * {@code rate-limiting.md}: "Redis (Bucket4j RedisBucket)" for Tiers 1-5's HTTP buckets. Builds its
 * own {@link RedisClient} from {@link DataRedisConnectionDetails} — the abstraction
 * {@code @ServiceConnection} overrides in tests — rather than reusing {@code
 * spring-boot-starter-data-redis}'s autoconfigured {@code LettuceConnectionFactory}, because
 * Bucket4j's proxy manager needs the raw Lettuce client, not Spring Data's repository-oriented
 * wrapper.
 */
@Configuration
class RedisRateLimiterConfig {

  /**
   * Longest Tier 1-5 window is one hour ({@code minting.*}); two hours of headroom keeps an idle
   * bucket's Redis key from outliving every window it could have mattered to, without expiring one
   * still mid-refill.
   */
  private static final Duration MAX_KEY_TTL = Duration.ofHours(2);

  @Bean(destroyMethod = "shutdown")
  RedisClient rateLimitRedisClient(DataRedisConnectionDetails connectionDetails) {
    DataRedisConnectionDetails.Standalone standalone = connectionDetails.getStandalone();
    if (standalone == null) {
      throw new IllegalStateException(
          "IMP-M1-02's rate limiter requires a standalone Redis connection"
              + " (spring.data.redis.host/port); no other topology is documented"
              + " (local-environment.md §7.9)");
    }
    RedisURI.Builder uri = RedisURI.Builder.redis(standalone.getHost(), standalone.getPort());
    String password = connectionDetails.getPassword();
    if (password != null) {
      uri.withPassword(password.toCharArray());
    }
    return RedisClient.create(uri.build());
  }

  @Bean
  ProxyManager<byte[]> rateLimitProxyManager(RedisClient rateLimitRedisClient) {
    return Bucket4jLettuce.casBasedBuilder(rateLimitRedisClient)
        .expirationAfterWrite(
            ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax(MAX_KEY_TTL))
        .build();
  }
}
