package io.turnbase.platform.common;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.mock.env.MockEnvironment;

class RateLimitEnabledProfileCheckTest {

  @Test
  void enabledPassesUnderAnyProfile() {
    var check =
        new RateLimitEnabledProfileCheck(new RateLimitProperties(true), new MockEnvironment());

    assertThatCode(() -> check.run(new DefaultApplicationArguments())).doesNotThrowAnyException();
  }

  @Test
  void disabledPassesUnderTestProfile() {
    MockEnvironment testEnv = new MockEnvironment();
    testEnv.setActiveProfiles("test");
    var check = new RateLimitEnabledProfileCheck(new RateLimitProperties(false), testEnv);

    assertThatCode(() -> check.run(new DefaultApplicationArguments())).doesNotThrowAnyException();
  }

  @Test
  void disabledPassesUnderE2eProfile() {
    MockEnvironment e2eEnv = new MockEnvironment();
    e2eEnv.setActiveProfiles("e2e");
    var check = new RateLimitEnabledProfileCheck(new RateLimitProperties(false), e2eEnv);

    assertThatCode(() -> check.run(new DefaultApplicationArguments())).doesNotThrowAnyException();
  }

  @Test
  void disabledFailsUnderLocalProfile() {
    MockEnvironment localEnv = new MockEnvironment();
    localEnv.setActiveProfiles("local");
    var check = new RateLimitEnabledProfileCheck(new RateLimitProperties(false), localEnv);

    assertThatThrownBy(() -> check.run(new DefaultApplicationArguments()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("turnbase.rate-limit.enabled");
  }
}
