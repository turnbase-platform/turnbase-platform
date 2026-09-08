package io.turnbase.platform.common;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.mock.env.MockEnvironment;

class RateLimitEnabledProfileCheckTest {

  @Test
  void enabledPassesUnderAnyProfile() {
    var check = new RateLimitEnabledProfileCheck(properties(true), new MockEnvironment());

    assertThatCode(() -> check.run(new DefaultApplicationArguments())).doesNotThrowAnyException();
  }

  @Test
  void disabledPassesUnderTestProfile() {
    MockEnvironment testEnv = new MockEnvironment();
    testEnv.setActiveProfiles("test");
    var check = new RateLimitEnabledProfileCheck(properties(false), testEnv);

    assertThatCode(() -> check.run(new DefaultApplicationArguments())).doesNotThrowAnyException();
  }

  @Test
  void disabledPassesUnderE2eProfile() {
    MockEnvironment e2eEnv = new MockEnvironment();
    e2eEnv.setActiveProfiles("e2e");
    var check = new RateLimitEnabledProfileCheck(properties(false), e2eEnv);

    assertThatCode(() -> check.run(new DefaultApplicationArguments())).doesNotThrowAnyException();
  }

  @Test
  void disabledFailsUnderLocalProfile() {
    MockEnvironment localEnv = new MockEnvironment();
    localEnv.setActiveProfiles("local");
    var check = new RateLimitEnabledProfileCheck(properties(false), localEnv);

    assertThatThrownBy(() -> check.run(new DefaultApplicationArguments()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("turnbase.rate-limit.enabled");
  }

  private static RateLimitProperties properties(boolean enabled) {
    return new RateLimitProperties(
        enabled,
        new RateLimitProperties.Auth(5, Duration.ofSeconds(30)),
        new RateLimitProperties.Bucket(100, Duration.ofSeconds(10)),
        new RateLimitProperties.Bucket(10, Duration.ofHours(1)),
        new RateLimitProperties.Bucket(200, Duration.ofMinutes(1)),
        new RateLimitProperties.Minting(
            new RateLimitProperties.Bucket(5, Duration.ofHours(1)),
            new RateLimitProperties.Bucket(3, Duration.ofHours(1))));
  }
}
