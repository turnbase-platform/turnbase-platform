package io.turnbase.platform.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

/**
 * {@code oauth-rest-api.md} §3 — the signed {@code state} carrying the OAuth redirect destination,
 * over the same key set {@link JwtService}'s session tokens use.
 */
class OAuthStateServiceTest {

  private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

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
  void issuedStateVerifiesBackToTheSameDestination(@TempDir Path tmp) {
    JwtKeySet keySet =
        new JwtKeySet(new JwtKeySet.ActiveKey("k1", randomSecret(), Instant.now()), List.of());
    OAuthStateService stateService = new OAuthStateService(newManager(keySet, tmp));
    Instant now = Instant.now();

    String state = stateService.issue("/rooms/abcd", now, now.plus(Duration.ofMinutes(5)));

    assertThat(stateService.verify(state))
        .isEqualTo(new OAuthStateService.VerifiedState("/rooms/abcd", null));
  }

  @Test
  void aLinkStateVerifiesBackToItsUserAndTheFixedSettingsDestination(@TempDir Path tmp) {
    JwtKeySet keySet =
        new JwtKeySet(new JwtKeySet.ActiveKey("k1", randomSecret(), Instant.now()), List.of());
    OAuthStateService stateService = new OAuthStateService(newManager(keySet, tmp));
    UUID userId = UUID.randomUUID();
    Instant now = Instant.now();

    String state = stateService.issueLink(userId, now, now.plus(Duration.ofMinutes(5)));

    assertThat(stateService.verify(state))
        .isEqualTo(new OAuthStateService.VerifiedState("/settings", userId));
  }

  @Test
  void anExpiredStateFailsVerification(@TempDir Path tmp) {
    JwtKeySet keySet =
        new JwtKeySet(new JwtKeySet.ActiveKey("k1", randomSecret(), Instant.now()), List.of());
    OAuthStateService stateService = new OAuthStateService(newManager(keySet, tmp));
    Instant issuedAt = Instant.now().minus(Duration.ofMinutes(10));

    String state = stateService.issue("/rooms", issuedAt, issuedAt.plus(Duration.ofMinutes(5)));

    assertThatThrownBy(() -> stateService.verify(state))
        .isInstanceOf(JwtVerificationException.class);
  }

  @Test
  void aStateNamingAKidRemovedFromTheSetFailsVerification(@TempDir Path tmp) {
    JwtKeySet.ActiveKey originalActive =
        new JwtKeySet.ActiveKey("k1", randomSecret(), Instant.now());
    JwtKeySetManager manager = newManager(new JwtKeySet(originalActive, List.of()), tmp);
    OAuthStateService stateService = new OAuthStateService(manager);
    Instant now = Instant.now();
    String state = stateService.issue("/rooms", now, now.plus(Duration.ofMinutes(5)));

    manager.promote();
    manager.retire("k1");

    assertThatThrownBy(() -> stateService.verify(state))
        .isInstanceOf(JwtVerificationException.class);
  }

  @Test
  void aTokenSignedForADifferentPurposeIsRejected(@TempDir Path tmp) {
    JwtKeySet keySet =
        new JwtKeySet(new JwtKeySet.ActiveKey("k1", randomSecret(), Instant.now()), List.of());
    JwtKeySetManager manager = newManager(keySet, tmp);
    JwtService jwtService = new JwtService(manager);
    OAuthStateService stateService = new OAuthStateService(manager);
    Instant now = Instant.now();
    // A session token carries sub/jti, never a "dst" claim.
    String sessionToken =
        jwtService.issue(
            java.util.UUID.randomUUID(),
            java.util.UUID.randomUUID(),
            now,
            now.plus(Duration.ofMinutes(5)));

    assertThatThrownBy(() -> stateService.verify(sessionToken))
        .isInstanceOf(JwtVerificationException.class);
  }

  private static String randomSecret() {
    byte[] bytes = new byte[32];
    new java.security.SecureRandom().nextBytes(bytes);
    return java.util.Base64.getEncoder().encodeToString(bytes);
  }
}
