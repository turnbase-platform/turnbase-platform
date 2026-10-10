package io.turnbase.platform;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.boot.data.redis.autoconfigure.DataRedisConnectionDetails;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * IMP-GATE-77: Spring Boot's {@code spring.data.redis.url} overrides {@code
 * spring.data.redis.password}, so a committed default for {@code url} makes {@code
 * SPRING_DATA_REDIS_PASSWORD} — the only way compose and Helm deliver the credential — a silent
 * no-op. The real {@code application.yml} is loaded so the default cannot drift back.
 */
class RedisConnectionConfigTest {

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withInitializer(new ConfigDataApplicationContextInitializer())
          .withConfiguration(AutoConfigurations.of(DataRedisAutoConfiguration.class));

  @Test
  void passwordSuppliedSeparatelyReachesTheConnection() {
    contextRunner
        .withPropertyValues("spring.data.redis.password=s3cret")
        .run(
            context -> {
              DataRedisConnectionDetails details =
                  context.getBean(DataRedisConnectionDetails.class);
              assertThat(details.getPassword()).isEqualTo("s3cret");
              var standalone = java.util.Objects.requireNonNull(details.getStandalone());
              assertThat(standalone.getHost()).isEqualTo("localhost");
              assertThat(standalone.getPort()).isEqualTo(6379);
            });
  }

  @Test
  void hostAndPortComeFromTheirOwnProperties() {
    contextRunner
        .withPropertyValues(
            "spring.data.redis.host=redis",
            "spring.data.redis.port=6380",
            "spring.data.redis.password=s3cret")
        .run(
            context -> {
              DataRedisConnectionDetails details =
                  context.getBean(DataRedisConnectionDetails.class);
              var standalone = java.util.Objects.requireNonNull(details.getStandalone());
              assertThat(standalone.getHost()).isEqualTo("redis");
              assertThat(standalone.getPort()).isEqualTo(6380);
              assertThat(details.getPassword()).isEqualTo("s3cret");
            });
  }
}
