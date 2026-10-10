package io.turnbase.platform.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.json.JsonMapper;

class JwtKeySetConfigTest {

  private final JsonMapper objectMapper = JsonMapper.builder().build();

  @Test
  void localProfileGeneratesAndPersistsToTheKeyFile(@TempDir Path tmp) {
    Path keyFile = tmp.resolve("jwt-keys.json");
    AuthProperties properties =
        new AuthProperties(
            Duration.ofHours(24),
            false,
            null,
            new AuthProperties.OAuth(Duration.ofMinutes(5), Duration.ofMinutes(10)));

    JwtKeySet generated =
        JwtKeySetConfig.resolve(properties, new MockEnvironment(), objectMapper, keyFile);

    assertThat(Files.exists(keyFile)).isTrue();
    assertThat(generated.active().kid()).isNotBlank();
    assertThat(generated.active().secret()).isNotBlank();
    assertThat(generated.retired()).isEmpty();
  }

  @Test
  void localProfileReusesAnExistingKeyFileAcrossRestarts(@TempDir Path tmp) throws Exception {
    Path keyFile = tmp.resolve("jwt-keys.json");
    AuthProperties properties =
        new AuthProperties(
            Duration.ofHours(24),
            false,
            null,
            new AuthProperties.OAuth(Duration.ofMinutes(5), Duration.ofMinutes(10)));
    JwtKeySet first =
        JwtKeySetConfig.resolve(properties, new MockEnvironment(), objectMapper, keyFile);

    JwtKeySet second =
        JwtKeySetConfig.resolve(properties, new MockEnvironment(), objectMapper, keyFile);

    assertThat(second).isEqualTo(first);
  }

  @Test
  void testProfileGeneratesAFreshKeyEveryTimeWithNoFile(@TempDir Path tmp) {
    Path keyFile = tmp.resolve("unused").resolve("jwt-keys.json");
    AuthProperties properties =
        new AuthProperties(
            Duration.ofHours(24),
            false,
            null,
            new AuthProperties.OAuth(Duration.ofMinutes(5), Duration.ofMinutes(10)));
    MockEnvironment testEnv = new MockEnvironment();
    testEnv.setActiveProfiles("test");

    JwtKeySet first = JwtKeySetConfig.resolve(properties, testEnv, objectMapper, keyFile);
    JwtKeySet second = JwtKeySetConfig.resolve(properties, testEnv, objectMapper, keyFile);

    assertThat(Files.exists(keyFile)).isFalse();
    assertThat(first.active().kid()).isNotEqualTo(second.active().secret());
    assertThat(first.active().secret()).isNotEqualTo(second.active().secret());
  }

  @Test
  void e2eProfileAlsoGeneratesInMemory(@TempDir Path tmp) {
    Path keyFile = tmp.resolve("jwt-keys.json");
    AuthProperties properties =
        new AuthProperties(
            Duration.ofHours(24),
            false,
            null,
            new AuthProperties.OAuth(Duration.ofMinutes(5), Duration.ofMinutes(10)));
    MockEnvironment e2eEnv = new MockEnvironment();
    e2eEnv.setActiveProfiles("e2e");

    JwtKeySetConfig.resolve(properties, e2eEnv, objectMapper, keyFile);

    assertThat(Files.exists(keyFile)).isFalse();
  }

  @Test
  void requiredWithNoSuppliedValueFailsNamingTheKey(@TempDir Path tmp) {
    AuthProperties properties =
        new AuthProperties(
            Duration.ofHours(24),
            true,
            null,
            new AuthProperties.OAuth(Duration.ofMinutes(5), Duration.ofMinutes(10)));

    assertThatThrownBy(
            () ->
                JwtKeySetConfig.resolve(
                    properties, new MockEnvironment(), objectMapper, tmp.resolve("jwt-keys.json")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("TURNBASE_JWT_KEY_SET");
  }

  @Test
  void requiredWithASuppliedValueParsesTwoConcurrentKeys(@TempDir Path tmp) throws Exception {
    JwtKeySet supplied =
        new JwtKeySet(
            new JwtKeySet.ActiveKey("k3", "active-secret", Instant.parse("2026-08-09T10:00:00Z")),
            List.of(
                new JwtKeySet.RetiredKey(
                    "k2", "retired-secret", Instant.parse("2026-08-09T10:00:00Z"))));
    AuthProperties properties =
        new AuthProperties(
            Duration.ofHours(24),
            true,
            objectMapper.writeValueAsString(supplied),
            new AuthProperties.OAuth(Duration.ofMinutes(5), Duration.ofMinutes(10)));

    JwtKeySet resolved =
        JwtKeySetConfig.resolve(
            properties, new MockEnvironment(), objectMapper, tmp.resolve("jwt-keys.json"));

    assertThat(resolved.secretFor("k3")).contains("active-secret");
    assertThat(resolved.secretFor("k2")).contains("retired-secret");
    assertThat(resolved.secretFor("nope")).isEmpty();
  }

  @Test
  void requiredWithMalformedJsonFailsClearly(@TempDir Path tmp) {
    AuthProperties properties =
        new AuthProperties(
            Duration.ofHours(24),
            true,
            "{not json",
            new AuthProperties.OAuth(Duration.ofMinutes(5), Duration.ofMinutes(10)));

    assertThatThrownBy(
            () ->
                JwtKeySetConfig.resolve(
                    properties, new MockEnvironment(), objectMapper, tmp.resolve("jwt-keys.json")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("not valid JSON");
  }
}
