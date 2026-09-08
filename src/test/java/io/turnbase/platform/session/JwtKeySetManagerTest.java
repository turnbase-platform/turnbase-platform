package io.turnbase.platform.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

class JwtKeySetManagerTest {

  private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

  private JwtKeySetManager managerFor(JwtKeySet initial, Path keyFile, boolean required) {
    AuthProperties properties =
        new AuthProperties(
            Duration.ofHours(24),
            required,
            null,
            new AuthProperties.OAuth(Duration.ofMinutes(5), Duration.ofMinutes(10)));
    return new JwtKeySetManager(initial, properties, new MockEnvironment(), objectMapper, keyFile);
  }

  @Test
  void promoteMovesTheOldActiveKeyToRetiredAndInstallsANewActiveKey(@TempDir Path tmp) {
    JwtKeySet initial =
        new JwtKeySet(new JwtKeySet.ActiveKey("k1", "secret-1", Instant.now()), List.of());
    JwtKeySetManager manager = managerFor(initial, tmp.resolve("jwt-keys.json"), false);

    JwtKeySet.ActiveKey promoted = manager.promote();

    JwtKeySet after = manager.current();
    assertThat(after.active()).isEqualTo(promoted);
    assertThat(after.active().kid()).isNotEqualTo("k1");
    assertThat(after.retired()).extracting(JwtKeySet.RetiredKey::kid).containsExactly("k1");
    assertThat(after.secretFor("k1")).contains("secret-1");
  }

  @Test
  void promotePersistsTheUpdatedSetToTheLocalFile(@TempDir Path tmp) throws Exception {
    Path keyFile = tmp.resolve("jwt-keys.json");
    Files.writeString(
        keyFile,
        objectMapper.writeValueAsString(
            new JwtKeySet(new JwtKeySet.ActiveKey("k1", "secret-1", Instant.now()), List.of())));
    JwtKeySetManager manager =
        managerFor(
            new JwtKeySet(new JwtKeySet.ActiveKey("k1", "secret-1", Instant.now()), List.of()),
            keyFile,
            false);

    manager.promote();

    JwtKeySet persisted = objectMapper.readValue(Files.readString(keyFile), JwtKeySet.class);
    assertThat(persisted).isEqualTo(manager.current());
  }

  @Test
  void requiredModeDoesNotPersistToAFile(@TempDir Path tmp) {
    Path keyFile = tmp.resolve("jwt-keys.json");
    JwtKeySet initial =
        new JwtKeySet(new JwtKeySet.ActiveKey("k1", "secret-1", Instant.now()), List.of());
    JwtKeySetManager manager = managerFor(initial, keyFile, true);

    manager.promote();

    assertThat(Files.exists(keyFile)).isFalse();
  }

  @Test
  void retireRemovesTheKeyFromTheRetiredList(@TempDir Path tmp) {
    JwtKeySet initial =
        new JwtKeySet(
            new JwtKeySet.ActiveKey("k2", "secret-2", Instant.now()),
            List.of(new JwtKeySet.RetiredKey("k1", "secret-1", Instant.now())));
    JwtKeySetManager manager = managerFor(initial, tmp.resolve("jwt-keys.json"), false);

    manager.retire("k1");

    assertThat(manager.current().retired()).isEmpty();
    assertThat(manager.current().secretFor("k1")).isEmpty();
    assertThat(manager.current().active().kid()).isEqualTo("k2");
  }

  @Test
  void retiringTheActiveKeyIsRejected(@TempDir Path tmp) {
    JwtKeySet initial =
        new JwtKeySet(new JwtKeySet.ActiveKey("k1", "secret-1", Instant.now()), List.of());
    JwtKeySetManager manager = managerFor(initial, tmp.resolve("jwt-keys.json"), false);

    assertThatThrownBy(() -> manager.retire("k1")).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void retiringAnUnknownKidIsRejected(@TempDir Path tmp) {
    JwtKeySet initial =
        new JwtKeySet(new JwtKeySet.ActiveKey("k1", "secret-1", Instant.now()), List.of());
    JwtKeySetManager manager = managerFor(initial, tmp.resolve("jwt-keys.json"), false);

    assertThatThrownBy(() -> manager.retire("nope")).isInstanceOf(IllegalArgumentException.class);
  }
}
