package io.turnbase.platform.session;

import static org.assertj.core.api.Assertions.assertThat;

import io.turnbase.platform.PostgresIntegrationTest;
import io.turnbase.platform.TurnBaseApplication;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * IMP-M1-03's own Done-when clause, checked end to end: rotation is exercised by a test that
 * verifies a token issued under the retired key still validates until its expiry, and that {@code
 * user_session.revoked_reason} records key-rotation revocation.
 *
 * <p>No registration/login endpoint exists yet ({@code IMP-M1-04}) to produce a real session, so
 * the fixture inserts a minimal {@code app_user}/{@code user_session} pair directly — this item
 * owns the key set and the session-revocation mechanics, not the endpoints that will create these
 * rows in the ordinary flow.
 */
class JwtKeyRotationServiceIT extends PostgresIntegrationTest {

  private ConfigurableApplicationContext context;
  private JdbcClient jdbcClient;
  private JwtService jwtService;
  private JwtKeyRotationService rotationService;
  private UserSessionRepository sessionRepository;

  @BeforeEach
  void bootApplication() {
    context =
        SpringApplication.run(
            TurnBaseApplication.class,
            "--server.port=0",
            "--management.server.port=0",
            "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
            "--spring.datasource.username=" + POSTGRES.getUsername(),
            "--spring.datasource.password=" + POSTGRES.getPassword(),
            "--turnbase.artifact-store.endpoint=" + MINIO.getS3URL(),
            "--turnbase.artifact-store.bucket=" + ARTIFACT_BUCKET,
            "--turnbase.artifact-store.access-key=" + MINIO.getUserName(),
            "--turnbase.artifact-store.secret-key=" + MINIO.getPassword(),
            "--turnbase.admin.username=admin",
            "--turnbase.admin.password=admin",
            "--spring.data.redis.url=" + redisUrl());
    jdbcClient = context.getBean(JdbcClient.class);
    jwtService = context.getBean(JwtService.class);
    rotationService = context.getBean(JwtKeyRotationService.class);
    sessionRepository = context.getBean(UserSessionRepository.class);
  }

  @AfterEach
  void stopApplication() {
    context.close();
  }

  @Test
  void retiringAKeyRevokesExactlyTheSessionsSignedUnderItAndLeavesOthersLive() {
    // Two distinct accounts — user_session_active_key permits only one live session per
    // user_id (FR-SESS-002), so the "untouched" session below needs an account of its own.
    UUID rotatedUserId = insertAppUser();
    UUID untouchedUserId = insertAppUser();
    UUID rotatedSessionId = UUID.randomUUID();
    UUID untouchedSessionId = UUID.randomUUID();
    Instant issuedAt = Instant.now();
    Instant expiresAt = issuedAt.plus(Duration.ofHours(24));

    String tokenUnderOriginalKey =
        jwtService.issue(rotatedUserId, rotatedSessionId, issuedAt, expiresAt);
    String rotatedKeyId = jwtService.verify(tokenUnderOriginalKey).keyId();
    sessionRepository.insert(
        new UserSession(
            rotatedSessionId, rotatedUserId, issuedAt, expiresAt, null, null, rotatedKeyId, null));

    // secrets-and-configuration.md §3.3 step 1: the original key moves to `retired`.
    rotationService.promote();

    // Graceful rotation revokes nothing yet — the token still validates under the retired key.
    assertThat(jwtService.verify(tokenUnderOriginalKey).keyId()).isEqualTo(rotatedKeyId);

    // A session bound to the new active key must not be touched by retiring the old one.
    String tokenUnderNewKey =
        jwtService.issue(untouchedUserId, untouchedSessionId, issuedAt, expiresAt);
    String newKeyId = jwtService.verify(tokenUnderNewKey).keyId();
    sessionRepository.insert(
        new UserSession(
            untouchedSessionId, untouchedUserId, issuedAt, expiresAt, null, null, newKeyId, null));

    // secrets-and-configuration.md §3.3 step 3: the retired key is removed and its sessions
    // revoked.
    int revokedCount = rotationService.retire(rotatedKeyId);

    assertThat(revokedCount).isEqualTo(1);
    UserSession rotatedSession = sessionRepository.findById(rotatedSessionId).orElseThrow();
    assertThat(rotatedSession.revokedReason()).isEqualTo("KEY_ROTATED");
    assertThat(rotatedSession.revokedAt()).isNotNull();

    UserSession untouchedSession = sessionRepository.findById(untouchedSessionId).orElseThrow();
    assertThat(untouchedSession.revokedReason()).isNull();
    assertThat(untouchedSession.revokedAt()).isNull();
  }

  private UUID insertAppUser() {
    UUID userId = UUID.randomUUID();
    String username = "guest-" + userId.toString().substring(0, 8);
    jdbcClient
        .sql(
            """
            insert into app_user (user_id, kind, username, username_skeleton, guest_serial)
            values (:userId, 'GUEST', :username, :usernameSkeleton, nextval('guest_serial_seq'))
            """)
        .param("userId", userId)
        .param("username", username)
        .param("usernameSkeleton", username)
        .update();
    return userId;
  }
}
