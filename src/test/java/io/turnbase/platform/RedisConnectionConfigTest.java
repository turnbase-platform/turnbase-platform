package io.turnbase.platform;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisConnectionDetails;
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
          .withConfiguration(AutoConfigurations.of(RedisAutoConfiguration.class));

  @Test
  void passwordSuppliedSeparatelyReachesTheConnection() {
    contextRunner
        .withPropertyValues("spring.data.redis.password=s3cret")
        .run(
            context -> {
              RedisConnectionDetails details = context.getBean(RedisConnectionDetails.class);
              assertThat(details.getPassword()).isEqualTo("s3cret");
              assertThat(details.getStandalone().getHost()).isEqualTo("localhost");
              assertThat(details.getStandalone().getPort()).isEqualTo(6379);
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
              RedisConnectionDetails details = context.getBean(RedisConnectionDetails.class);
              assertThat(details.getStandalone().getHost()).isEqualTo("redis");
              assertThat(details.getStandalone().getPort()).isEqualTo(6380);
              assertThat(details.getPassword()).isEqualTo("s3cret");
            });
  }
}
