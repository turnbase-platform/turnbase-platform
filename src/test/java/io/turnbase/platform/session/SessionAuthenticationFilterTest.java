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
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * {@code IMP-M1-06}: the filter resolves a principal (or records why it could not) but never itself
 * rejects a request — {@link SessionAuthenticationInterceptor} owns the 401. Token-verify-then-
 * session-lookup itself is {@link SessionServiceTest}'s concern since {@code IMP-M2-02} moved it
 * onto {@link SessionService#resolve}; this class only exercises how the filter dispatches on the
 * two {@link SessionService.Resolution} cases.
 */
class SessionAuthenticationFilterTest {

  private final SessionService sessionService = mock(SessionService.class);
  private final SessionAuthenticationFilter filter =
      new SessionAuthenticationFilter(sessionService);

  @Test
  void aRequestWithNoCookiePassesThroughWithNoPrincipalAndNoFailureReason() throws Exception {
    HttpServletRequest seen = run(new MockHttpServletRequest());

    assertThat(seen.getUserPrincipal()).isNull();
    assertThat(seen.getAttribute(SessionAuthenticationFilter.AUTH_FAILURE_REASON_ATTRIBUTE))
        .isNull();
  }

  @Test
  void anInvalidResolutionIsRecordedAsTheFailureReasonAndTheRequestStillProceeds()
      throws Exception {
    when(sessionService.resolve("garbage"))
        .thenReturn(new SessionService.Resolution.Invalid(SessionService.INVALID_REASON));

    HttpServletRequest seen = run(requestWithCookie("garbage"));

    assertThat(seen.getUserPrincipal()).isNull();
    assertThat(seen.getAttribute(SessionAuthenticationFilter.AUTH_FAILURE_REASON_ATTRIBUTE))
        .isEqualTo(SessionAuthenticationFilter.INVALID_REASON);
  }

  @Test
  void aRevokedResolutionCarriesItsOwnRevokedReason() throws Exception {
    when(sessionService.resolve("token"))
        .thenReturn(new SessionService.Resolution.Invalid("DISPLACED"));

    HttpServletRequest seen = run(requestWithCookie("token"));

    assertThat(seen.getUserPrincipal()).isNull();
    assertThat(seen.getAttribute(SessionAuthenticationFilter.AUTH_FAILURE_REASON_ATTRIBUTE))
        .isEqualTo("DISPLACED");
  }

  @Test
  void aValidResolutionEstablishesTheAuthenticatedUserPrincipal() throws Exception {
    UUID userId = UUID.randomUUID();
    UUID sessionId = UUID.randomUUID();
    AuthenticatedUser principal = new AuthenticatedUser(userId, sessionId);
    when(sessionService.resolve("token"))
        .thenReturn(new SessionService.Resolution.Valid(principal));

    HttpServletRequest seen = run(requestWithCookie("token"));

    assertThat(seen.getUserPrincipal()).isEqualTo(principal);
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
