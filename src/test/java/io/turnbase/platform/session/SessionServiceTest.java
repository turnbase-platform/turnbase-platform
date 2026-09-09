package io.turnbase.platform.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.turnbase.platform.common.AuthenticatedUser;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * {@code IMP-M2-02}: {@link SessionService#resolve} is the session-cookie validation logic {@code
 * SessionAuthenticationFilter} used to own directly — moved here so the WebSocket {@code CONNECT}
 * path can call the identical check without duplicating token-verify-then-session-lookup.
 */
class SessionServiceTest {

  private final JwtService jwtService = mock(JwtService.class);
  private final UserSessionRepository sessions = mock(UserSessionRepository.class);
  private final AuthProperties authProperties = mock(AuthProperties.class);
  private final PlatformTransactionManager transactionManager =
      mock(PlatformTransactionManager.class);
  private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
  private final SessionService sessionService =
      new SessionService(sessions, jwtService, authProperties, transactionManager, eventPublisher);

  @Test
  void anUnverifiableTokenResolvesAsInvalid() {
    when(jwtService.verify("garbage")).thenThrow(new JwtVerificationException("bad signature"));

    SessionService.Resolution resolution = sessionService.resolve("garbage");

    assertThat(resolution)
        .isEqualTo(new SessionService.Resolution.Invalid(SessionService.INVALID_REASON));
  }

  @Test
  void aTokenWithNoMatchingSessionRowResolvesAsInvalid() {
    UUID userId = UUID.randomUUID();
    UUID sessionId = UUID.randomUUID();
    when(jwtService.verify("token"))
        .thenReturn(new JwtClaims(userId, sessionId, "k1", Instant.now(), Instant.now()));
    when(sessions.findById(sessionId)).thenReturn(Optional.empty());

    SessionService.Resolution resolution = sessionService.resolve("token");

    assertThat(resolution)
        .isEqualTo(new SessionService.Resolution.Invalid(SessionService.INVALID_REASON));
  }

  @Test
  void aRevokedSessionResolvesWithItsOwnRevokedReason() {
    UUID userId = UUID.randomUUID();
    UUID sessionId = UUID.randomUUID();
    when(jwtService.verify("token"))
        .thenReturn(new JwtClaims(userId, sessionId, "k1", Instant.now(), Instant.now()));
    when(sessions.findById(sessionId))
        .thenReturn(
            Optional.of(
                new UserSession(
                    sessionId,
                    userId,
                    Instant.now().minusSeconds(60),
                    Instant.now().plusSeconds(3600),
                    Instant.now(),
                    "DISPLACED",
                    "k1",
                    null)));

    SessionService.Resolution resolution = sessionService.resolve("token");

    assertThat(resolution).isEqualTo(new SessionService.Resolution.Invalid("DISPLACED"));
  }

  @Test
  void anExpiredButUnrevokedSessionResolvesAsExpired() {
    UUID userId = UUID.randomUUID();
    UUID sessionId = UUID.randomUUID();
    when(jwtService.verify("token"))
        .thenReturn(new JwtClaims(userId, sessionId, "k1", Instant.now(), Instant.now()));
    when(sessions.findById(sessionId))
        .thenReturn(
            Optional.of(
                new UserSession(
                    sessionId,
                    userId,
                    Instant.now().minus(48, ChronoUnit.HOURS),
                    Instant.now().minus(24, ChronoUnit.HOURS),
                    null,
                    null,
                    "k1",
                    null)));

    SessionService.Resolution resolution = sessionService.resolve("token");

    assertThat(resolution)
        .isEqualTo(new SessionService.Resolution.Invalid(SessionService.EXPIRED_REASON));
  }

  @Test
  void aValidTokenResolvesToTheAuthenticatedUser() {
    UUID userId = UUID.randomUUID();
    UUID sessionId = UUID.randomUUID();
    when(jwtService.verify("token"))
        .thenReturn(new JwtClaims(userId, sessionId, "k1", Instant.now(), Instant.now()));
    when(sessions.findById(sessionId))
        .thenReturn(
            Optional.of(
                new UserSession(
                    sessionId,
                    userId,
                    Instant.now().minusSeconds(60),
                    Instant.now().plusSeconds(3600),
                    null,
                    null,
                    "k1",
                    null)));

    SessionService.Resolution resolution = sessionService.resolve("token");

    assertThat(resolution)
        .isEqualTo(new SessionService.Resolution.Valid(new AuthenticatedUser(userId, sessionId)));
  }
}
