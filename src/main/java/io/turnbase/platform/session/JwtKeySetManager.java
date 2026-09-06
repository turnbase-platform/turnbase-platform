package io.turnbase.platform.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * The mutable half of the key set {@link JwtKeySetConfig} resolves at startup —
 * secrets-and-configuration.md §3.3's three-step rotation (promote, wait, retire) needs a holder
 * that can change while the application keeps running, which the immutable {@link JwtKeySet} bean
 * alone cannot express.
 *
 * <p>Persistence mirrors {@link JwtKeySetConfig}'s own environment split: the {@code local} row
 * (bare process or compose) writes the rotated set back to {@code .secrets/jwt-keys.json} so a
 * restart does not undo a rotation; {@code test}/{@code e2e} and the {@code
 * turnbase.auth.jwt-key-set-required} row (Kubernetes) only ever held the set in memory to begin
 * with, and rotating it here does not create a file that was never part of their contract. The
 * Kubernetes case is unbuilt production escalation (§7): reconciling a rotation into the cluster's
 * own {@code Secret} is a human action, not something this process does to itself.
 */
public class JwtKeySetManager {

  private static final int SECRET_BYTES = 32;

  private final AtomicReference<JwtKeySet> current;
  private final ObjectMapper objectMapper;
  private final Path localKeyFile;
  private final boolean persistToFile;

  JwtKeySetManager(
      JwtKeySet initial,
      AuthProperties properties,
      Environment environment,
      ObjectMapper objectMapper,
      Path localKeyFile) {
    this.current = new AtomicReference<>(initial);
    this.objectMapper = objectMapper;
    this.localKeyFile = localKeyFile;
    this.persistToFile =
        !properties.jwtKeySetRequired() && !environment.matchesProfiles("test", "e2e");
  }

  public JwtKeySet current() {
    // The reference is never set to null: the constructor and both mutators always store a
    // freshly-built JwtKeySet. AtomicReference<T>#get() is unannotated, not genuinely nullable.
    return Objects.requireNonNull(current.get());
  }

  /**
   * secrets-and-configuration.md §3.3 step 1: a new key becomes {@code active}; the previous {@code
   * active} moves to {@code retired}. Existing tokens keep verifying.
   */
  public synchronized JwtKeySet.ActiveKey promote() {
    JwtKeySet before = current();
    JwtKeySet.ActiveKey newActive = generateKey();
    List<JwtKeySet.RetiredKey> retired = new ArrayList<>(before.retired());
    retired.add(
        new JwtKeySet.RetiredKey(before.active().kid(), before.active().secret(), Instant.now()));
    JwtKeySet updated = new JwtKeySet(newActive, List.copyOf(retired));
    current.set(updated);
    persist(updated);
    return newActive;
  }

  /**
   * secrets-and-configuration.md §3.3 step 3: the key is removed from the set. The caller is
   * responsible for revoking any session still naming it ({@link JwtKeyRotationService}) — this
   * method only owns the key set itself.
   *
   * @throws IllegalArgumentException if {@code kid} is the active key or names no retired key
   */
  public synchronized void retire(String kid) {
    JwtKeySet before = current();
    if (before.active().kid().equals(kid)) {
      throw new IllegalArgumentException("cannot retire the active key: " + kid);
    }
    List<JwtKeySet.RetiredKey> remaining =
        before.retired().stream().filter(k -> !k.kid().equals(kid)).toList();
    if (remaining.size() == before.retired().size()) {
      throw new IllegalArgumentException("no retired key with kid " + kid);
    }
    JwtKeySet updated = new JwtKeySet(before.active(), remaining);
    current.set(updated);
    persist(updated);
  }

  private void persist(JwtKeySet updated) {
    if (!persistToFile) {
      return;
    }
    try {
      Path parent = localKeyFile.getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      Files.writeString(localKeyFile, objectMapper.writeValueAsString(updated));
    } catch (IOException e) {
      throw new IllegalStateException("Could not persist rotated key set to " + localKeyFile, e);
    }
  }

  private static JwtKeySet.ActiveKey generateKey() {
    byte[] secretBytes = new byte[SECRET_BYTES];
    new SecureRandom().nextBytes(secretBytes);
    String secret = Base64.getEncoder().encodeToString(secretBytes);
    return new JwtKeySet.ActiveKey("k" + Instant.now().toEpochMilli(), secret, Instant.now());
  }

  @Configuration
  static class JwtKeySetManagerConfig {

    @Bean
    JwtKeySetManager jwtKeySetManager(
        JwtKeySet initial,
        AuthProperties properties,
        Environment environment,
        ObjectMapper objectMapper) {
      return new JwtKeySetManager(
          initial, properties, environment, objectMapper, JwtKeySetConfig.LOCAL_KEY_FILE);
    }
  }
}
