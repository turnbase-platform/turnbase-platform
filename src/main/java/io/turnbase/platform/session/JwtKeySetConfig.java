package io.turnbase.platform.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * secrets-and-configuration.md §3.1: {@code local} generates a key and persists it so a restart
 * does not log the developer out; {@code test}/{@code e2e} generate a fresh key in memory every
 * run, since deterministic sessions across runs are not wanted; everything else — including the
 * canonical Kubernetes chart, which runs the identical {@code local} Spring profile — requires
 * {@code turnbase.auth.jwt-key-set-required=true} and a supplied {@code TURNBASE_JWT_KEY_SET},
 * failing loudly if it is absent (IMP-GATE-16).
 */
@Configuration
public class JwtKeySetConfig {

  static final Path LOCAL_KEY_FILE = Path.of(".secrets", "jwt-keys.json");
  private static final int SECRET_BYTES = 32;
  private static final String FIRST_KID = "k1";

  @Bean
  public JwtKeySet jwtKeySet(
      AuthProperties properties, Environment environment, ObjectMapper objectMapper) {
    return resolve(properties, environment, objectMapper, LOCAL_KEY_FILE);
  }

  static JwtKeySet resolve(
      AuthProperties properties,
      Environment environment,
      ObjectMapper objectMapper,
      Path localKeyFile) {
    if (properties.jwtKeySetRequired()) {
      return requireSupplied(properties.jwtKeySet(), objectMapper);
    }
    if (environment.matchesProfiles("test", "e2e")) {
      return generate();
    }
    return loadOrGenerateLocal(objectMapper, localKeyFile);
  }

  private static JwtKeySet requireSupplied(@Nullable String supplied, ObjectMapper objectMapper) {
    if (supplied == null || supplied.isBlank()) {
      throw new IllegalStateException(
          "turnbase.auth.jwt-key-set-required is true but TURNBASE_JWT_KEY_SET is not set"
              + " (secrets-and-configuration.md §3.1)");
    }
    return parse(supplied, objectMapper);
  }

  private static JwtKeySet loadOrGenerateLocal(ObjectMapper objectMapper, Path localKeyFile) {
    try {
      if (Files.exists(localKeyFile)) {
        return parse(Files.readString(localKeyFile), objectMapper);
      }
      JwtKeySet generated = generate();
      Path parent = localKeyFile.getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      Files.writeString(localKeyFile, objectMapper.writeValueAsString(generated));
      return generated;
    } catch (IOException e) {
      throw new IllegalStateException("Could not read or write " + localKeyFile, e);
    }
  }

  private static JwtKeySet parse(String json, ObjectMapper objectMapper) {
    try {
      return objectMapper.readValue(json, JwtKeySet.class);
    } catch (IOException e) {
      throw new IllegalStateException("TURNBASE_JWT_KEY_SET is not valid JSON", e);
    }
  }

  private static JwtKeySet generate() {
    byte[] secretBytes = new byte[SECRET_BYTES];
    new SecureRandom().nextBytes(secretBytes);
    String secret = Base64.getEncoder().encodeToString(secretBytes);
    return new JwtKeySet(new JwtKeySet.ActiveKey(FIRST_KID, secret, Instant.now()), List.of());
  }
}
