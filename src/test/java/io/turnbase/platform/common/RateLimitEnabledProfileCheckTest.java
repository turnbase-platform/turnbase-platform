package io.turnbase.platform.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.turnbase.platform.websocket.WebSocketRateLimitProperties;
import java.lang.reflect.RecordComponent;
import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.mock.env.MockEnvironment;

class RateLimitEnabledProfileCheckTest {

  private static final String API = "turnbase.rate-limit.switches.api";
  private static final String WS_CHAT = "turnbase.rate-limit.ws.switches.chat";

  @Test
  void enabledPassesUnderAnyProfile() {
    var check = new RateLimitEnabledProfileCheck(properties(true), new MockEnvironment());

    assertThatCode(() -> check.run(new DefaultApplicationArguments())).doesNotThrowAnyException();
  }

  @Test
  void allSwitchesOnPassUnderAnyProfile() {
    MockEnvironment env = new MockEnvironment().withProperty(API, "true");
    env.setActiveProfiles("local");
    var check = new RateLimitEnabledProfileCheck(properties(true), env);

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

  @Test
  void masterOffStillFailsUnderLocalProfile() {
    MockEnvironment localEnv = new MockEnvironment().withProperty(API, "true");
    localEnv.setActiveProfiles("local");
    var check = new RateLimitEnabledProfileCheck(properties(false), localEnv);

    assertThatThrownBy(() -> check.run(new DefaultApplicationArguments()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("turnbase.rate-limit.enabled");
  }

  @Test
  void perMechanismSwitchOffFailsUnderLocalProfile() {
    for (String key : RateLimitEnabledProfileCheck.SWITCH_KEYS) {
      MockEnvironment env = new MockEnvironment().withProperty(key, "false");
      env.setActiveProfiles("local");
      var check = new RateLimitEnabledProfileCheck(properties(true), env);

      assertThatThrownBy(() -> check.run(new DefaultApplicationArguments()))
          .as(key)
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining(key);
    }
  }

  @Test
  void perMechanismSwitchOffFailsWithNoProfile() {
    var env = new MockEnvironment().withProperty(API, "false");
    var check = new RateLimitEnabledProfileCheck(properties(true), env);

    assertThatThrownBy(() -> check.run(new DefaultApplicationArguments()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(API);
  }

  @Test
  void severalSwitchesOffAreAllNamedInTheMessage() {
    MockEnvironment env =
        new MockEnvironment().withProperty(API, "false").withProperty(WS_CHAT, "false");
    env.setActiveProfiles("local");
    var check = new RateLimitEnabledProfileCheck(properties(false), env);

    assertThatThrownBy(() -> check.run(new DefaultApplicationArguments()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("turnbase.rate-limit.enabled")
        .hasMessageContaining(API)
        .hasMessageContaining(WS_CHAT);
  }

  @Test
  void perMechanismSwitchOffPassesUnderTestE2eAndLoadtestProfiles() {
    for (String profile : new String[] {"test", "e2e", "loadtest"}) {
      MockEnvironment env = new MockEnvironment();
      RateLimitEnabledProfileCheck.SWITCH_KEYS.forEach(key -> env.setProperty(key, "false"));
      env.setActiveProfiles(profile);
      var check = new RateLimitEnabledProfileCheck(properties(true), env);

      assertThatCode(() -> check.run(new DefaultApplicationArguments()))
          .as(profile)
          .doesNotThrowAnyException();
    }
  }

  @Test
  void perMechanismSwitchOffPassesUnderLoadtestProfile() {
    MockEnvironment env = new MockEnvironment().withProperty(API, "false");
    env.setActiveProfiles("local", "loadtest");
    var check = new RateLimitEnabledProfileCheck(properties(true), env);

    assertThatCode(() -> check.run(new DefaultApplicationArguments())).doesNotThrowAnyException();
  }

  /** A switch added to either record but missing from the guard's key list would escape it. */
  @Test
  void everySwitchComponentIsInTheProfileGuardKeyList() {
    Set<String> components =
        Stream.concat(
                keysOf(RateLimitProperties.Switches.class, "turnbase.rate-limit.switches."),
                keysOf(
                    WebSocketRateLimitProperties.Switches.class,
                    "turnbase.rate-limit.ws.switches."))
            .collect(Collectors.toSet());

    assertThat(components)
        .containsExactlyInAnyOrderElementsOf(RateLimitEnabledProfileCheck.SWITCH_KEYS);
  }

  private static Stream<String> keysOf(Class<? extends Record> switches, String prefix) {
    return Arrays.stream(switches.getRecordComponents())
        .map(RecordComponent::getName)
        .map(
            name ->
                // `publicTier` is bound as `public` (a Java keyword), see RateLimitProperties.
                prefix + ("publicTier".equals(name) ? "public" : kebab(name)));
  }

  private static String kebab(String camel) {
    return camel.replaceAll("([A-Z])", "-$1").toLowerCase(Locale.ROOT);
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
            new RateLimitProperties.Bucket(3, Duration.ofHours(1))),
        RateLimitProperties.Switches.allEnabled());
  }
}
