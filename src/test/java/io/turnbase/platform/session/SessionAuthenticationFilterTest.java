package io.turnbase.platform.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.turnbase.platform.common.AuthenticatedUser;
import io.turnbase.platform.common.SessionCookie;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * {@code IMP-M1-06}: the filter resolves a principal (or records why it could not) but never itself
 * rejects a request — {@link SessionAuthenticationInterceptor} owns the 401.
 */
class SessionAuthenticationFilterTest {

  private final JwtService jwtService = mock(JwtService.class);
  private final UserSessionRepository sessions = mock(UserSessionRepository.class);
  private final SessionAuthenticationFilter filter =
      new SessionAuthenticationFilter(jwtService, sessions);

  @Test
  void aRequestWithNoCookiePassesThroughWithNoPrincipalAndNoFailureReason() throws Exception {
    HttpServletRequest seen = run(new MockHttpServletRequest());

    assertThat(seen.getUserPrincipal()).isNull();
    assertThat(seen.getAttribute(SessionAuthenticationFilter.AUTH_FAILURE_REASON_ATTRIBUTE))
        .isNull();
  }

  @Test
  void anUnverifiableTokenIsRecordedAsInvalidAndTheRequestStillProceeds() throws Exception {
    when(jwtService.verify("garbage")).thenThrow(new JwtVerificationException("bad signature"));

    HttpServletRequest seen = run(requestWithCookie("garbage"));

    assertThat(seen.getUserPrincipal()).isNull();
    assertThat(seen.getAttribute(SessionAuthenticationFilter.AUTH_FAILURE_REASON_ATTRIBUTE))
        .isEqualTo(SessionAuthenticationFilter.INVALID_REASON);
  }

  @Test
  void aTokenWithNoMatchingSessionRowIsRecordedAsInvalid() throws Exception {
    UUID userId = UUID.randomUUID();
    UUID sessionId = UUID.randomUUID();
    when(jwtService.verify("token"))
        .thenReturn(new JwtClaims(userId, sessionId, "k1", Instant.now(), Instant.now()));
    when(sessions.findById(sessionId)).thenReturn(Optional.empty());

    HttpServletRequest seen = run(requestWithCookie("token"));

    assertThat(seen.getUserPrincipal()).isNull();
    assertThat(seen.getAttribute(SessionAuthenticationFilter.AUTH_FAILURE_REASON_ATTRIBUTE))
        .isEqualTo(SessionAuthenticationFilter.INVALID_REASON);
  }

  @Test
  void aRevokedSessionIsRecordedWithItsOwnRevokedReason() throws Exception {
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

    HttpServletRequest seen = run(requestWithCookie("token"));

    assertThat(seen.getUserPrincipal()).isNull();
    assertThat(seen.getAttribute(SessionAuthenticationFilter.AUTH_FAILURE_REASON_ATTRIBUTE))
        .isEqualTo("DISPLACED");
  }

  @Test
  void anExpiredButUnrevokedSessionIsRecordedAsExpired() throws Exception {
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

    HttpServletRequest seen = run(requestWithCookie("token"));

    assertThat(seen.getUserPrincipal()).isNull();
    assertThat(seen.getAttribute(SessionAuthenticationFilter.AUTH_FAILURE_REASON_ATTRIBUTE))
        .isEqualTo(SessionAuthenticationFilter.EXPIRED_REASON);
  }

  @Test
  void aValidTokenEstablishesTheAuthenticatedUserPrincipal() throws Exception {
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

    HttpServletRequest seen = run(requestWithCookie("token"));

    assertThat(seen.getUserPrincipal()).isEqualTo(new AuthenticatedUser(userId, sessionId));
  }

  private HttpServletRequest run(MockHttpServletRequest request)
      throws ServletException, IOException {
    CapturingChain chain = new CapturingChain();
    filter.doFilter(request, new MockHttpServletResponse(), chain);
    return chain.seenRequest;
  }

  private static MockHttpServletRequest requestWithCookie(String token) {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setCookies(new Cookie(SessionCookie.NAME, token));
    return request;
  }

  private static final class CapturingChain implements FilterChain {
    private HttpServletRequest seenRequest = new MockHttpServletRequest();

    @Override
    public void doFilter(ServletRequest request, ServletResponse response) {
      this.seenRequest = (HttpServletRequest) request;
    }
  }
}
