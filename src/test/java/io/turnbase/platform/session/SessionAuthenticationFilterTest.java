package io.turnbase.platform.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.turnbase.platform.common.AuthenticatedUser;
import io.turnbase.platform.common.PlatformOriginProperties;
import io.turnbase.platform.common.SessionCookie;
import io.turnbase.platform.common.SessionCookies;
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
  private final SessionCookies plainCookies =
      new SessionCookies(new PlatformOriginProperties("http://turnbase.localhost:8080"));
  private final SessionAuthenticationFilter filter =
      new SessionAuthenticationFilter(sessionService, plainCookies);

  @Test
  void underTlsOnlyTheHostPrefixedCookieIsRead() throws Exception {
    SessionCookies tls = new SessionCookies(new PlatformOriginProperties("https://turnbase.io"));
    SessionAuthenticationFilter tlsFilter = new SessionAuthenticationFilter(sessionService, tls);
    MockHttpServletRequest plainName = requestWithCookie("tok");
    MockHttpServletRequest hostName = new MockHttpServletRequest();
    hostName.setCookies(new Cookie("__Host-turnbase_session", "tok"));
    when(sessionService.resolve("tok"))
        .thenReturn(new SessionService.Resolution.Invalid("invalid"));

    CapturingChain chain = new CapturingChain();
    tlsFilter.doFilter(plainName, new MockHttpServletResponse(), chain);
    assertThat(
            chain.seenRequest.getAttribute(
                SessionAuthenticationFilter.AUTH_FAILURE_REASON_ATTRIBUTE))
        .isNull();
    tlsFilter.doFilter(hostName, new MockHttpServletResponse(), chain);
    assertThat(
            chain.seenRequest.getAttribute(
                SessionAuthenticationFilter.AUTH_FAILURE_REASON_ATTRIBUTE))
        .isEqualTo("invalid");
  }

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
