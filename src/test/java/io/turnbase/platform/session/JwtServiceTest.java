package io.turnbase.platform.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.json.JsonMapper;

/**
 * secrets-and-configuration.md §3.2/§3.3: a token issued under a key that is later retired must
 * keep validating until its own expiry — that is what makes rotation invisible instead of a mass
 * logout.
 */
class JwtServiceTest {

  private final JsonMapper objectMapper = JsonMapper.builder().build();

  private JwtKeySetManager newManager(JwtKeySet initial, Path tmp) {
    AuthProperties properties =
        new AuthProperties(
            Duration.ofHours(24),
            false,
            null,
            new AuthProperties.OAuth(Duration.ofMinutes(5), Duration.ofMinutes(10)));
    return new JwtKeySetManager(
        initial, properties, new MockEnvironment(), objectMapper, tmp.resolve("jwt-keys.json"));
  }

  @Test
  void issuedTokenCarriesTheActiveKeyIdAndVerifiesBackToTheSameClaims(@TempDir Path tmp) {
    JwtKeySet keySet =
        new JwtKeySet(new JwtKeySet.ActiveKey("k1", randomSecret(), Instant.now()), List.of());
    JwtService jwtService = new JwtService(newManager(keySet, tmp));
    UUID userId = UUID.randomUUID();
    UUID sessionId = UUID.randomUUID();
    Instant issuedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    Instant expiresAt = issuedAt.plus(Duration.ofHours(24));

    String token = jwtService.issue(userId, sessionId, issuedAt, expiresAt);
    JwtClaims claims = jwtService.verify(token);

    assertThat(claims.userId()).isEqualTo(userId);
    assertThat(claims.sessionId()).isEqualTo(sessionId);
    assertThat(claims.keyId()).isEqualTo("k1");
    assertThat(claims.issuedAt()).isEqualTo(issuedAt);
    assertThat(claims.expiresAt()).isEqualTo(expiresAt);
  }

  @Test
  void aTokenSignedUnderARetiredKeyStillVerifiesUntilItsExpiry(@TempDir Path tmp) {
    JwtKeySet.ActiveKey originalActive =
        new JwtKeySet.ActiveKey("k1", randomSecret(), Instant.now());
    JwtKeySetManager manager = newManager(new JwtKeySet(originalActive, List.of()), tmp);
    JwtService jwtService = new JwtService(manager);
    Instant issuedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    Instant expiresAt = issuedAt.plus(Duration.ofHours(24));
    String tokenSignedByK1 =
        jwtService.issue(UUID.randomUUID(), UUID.randomUUID(), issuedAt, expiresAt);

    // secrets-and-configuration.md §3.3 step 1: k1 is promoted out of `active` into `retired`.
    manager.promote();

    JwtClaims claims = jwtService.verify(tokenSignedByK1);
    assertThat(claims.keyId()).isEqualTo("k1");
    // New tokens are now signed with the new active key, not the retired one.
    String tokenSignedByNewActive =
        jwtService.issue(UUID.randomUUID(), UUID.randomUUID(), issuedAt, expiresAt);
    assertThat(jwtService.verify(tokenSignedByNewActive).keyId()).isNotEqualTo("k1");
  }

  @Test
  void aTokenNamingAKidRemovedFromTheSetFailsVerification(@TempDir Path tmp) {
    JwtKeySet.ActiveKey originalActive =
        new JwtKeySet.ActiveKey("k1", randomSecret(), Instant.now());
    JwtKeySetManager manager = newManager(new JwtKeySet(originalActive, List.of()), tmp);
    JwtService jwtService = new JwtService(manager);
    Instant issuedAt = Instant.now();
    String token =
        jwtService.issue(
            UUID.randomUUID(), UUID.randomUUID(), issuedAt, issuedAt.plus(Duration.ofHours(24)));
    manager.promote();
    manager.retire("k1");

    assertThatThrownBy(() -> jwtService.verify(token)).isInstanceOf(JwtVerificationException.class);
  }

  @Test
  void anExpiredTokenFailsVerification(@TempDir Path tmp) {
    JwtKeySet keySet =
        new JwtKeySet(new JwtKeySet.ActiveKey("k1", randomSecret(), Instant.now()), List.of());
    JwtService jwtService = new JwtService(newManager(keySet, tmp));
    Instant issuedAt = Instant.now().minus(Duration.ofHours(48));
    String token =
        jwtService.issue(
            UUID.randomUUID(), UUID.randomUUID(), issuedAt, issuedAt.plus(Duration.ofHours(24)));

    assertThatThrownBy(() -> jwtService.verify(token)).isInstanceOf(JwtVerificationException.class);
  }

  private static String randomSecret() {
    byte[] bytes = new byte[32];
    new java.security.SecureRandom().nextBytes(bytes);
    return java.util.Base64.getEncoder().encodeToString(bytes);
  }
}
