package io.turnbase.platform.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.turnbase.platform.common.AuthenticatedUser;
import io.turnbase.platform.common.UnauthenticatedException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * {@code rest-conventions.md} § Authentication / {@code user-accounts.md} NFR-SEC-006, FR-SESS-004.
 */
class SessionAuthenticationInterceptorTest {

  private final SessionAuthenticationInterceptor interceptor =
      new SessionAuthenticationInterceptor();

  @Test
  void anAuthenticatedRequestPasses() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setUserPrincipal(new AuthenticatedUser(UUID.randomUUID(), UUID.randomUUID()));

    boolean result = interceptor.preHandle(request, new MockHttpServletResponse(), new Object());

    assertThat(result).isTrue();
  }

  @Test
  void registrationAndLoginAreExemptWithNoPrincipal() {
    for (String path : new String[] {"/api/auth/register", "/api/auth/login"}) {
      MockHttpServletRequest request = new MockHttpServletRequest("POST", path);

      boolean result = interceptor.preHandle(request, new MockHttpServletResponse(), new Object());

      assertThat(result).as(path).isTrue();
    }
  }

  @Test
  void theProviderAvailabilityReadIsExemptWithNoPrincipal() {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/auth/providers");

    boolean result = interceptor.preHandle(request, new MockHttpServletResponse(), new Object());

    assertThat(result).isTrue();
  }

  @Test
  void anUnauthenticatedRequestToAProtectedPathWithNoCookieIsRejected() {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/auth/session");

    assertThatThrownBy(
            () -> interceptor.preHandle(request, new MockHttpServletResponse(), new Object()))
        .isInstanceOf(UnauthenticatedException.class)
        .hasMessage("Authentication is required for this endpoint.");
  }

  @Test
  void aDisplacedSessionNamesTheOtherDeviceLoginPerFrSess004() {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/auth/session");
    request.setAttribute(SessionAuthenticationFilter.AUTH_FAILURE_REASON_ATTRIBUTE, "DISPLACED");

    assertThatThrownBy(
            () -> interceptor.preHandle(request, new MockHttpServletResponse(), new Object()))
        .isInstanceOf(UnauthenticatedException.class)
        .hasMessage("Your session was ended because you logged in from another device.");
  }

  @Test
  void anExpiredSessionSaysSoRatherThanTheGenericMessage() {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/auth/session");
    request.setAttribute(
        SessionAuthenticationFilter.AUTH_FAILURE_REASON_ATTRIBUTE,
        SessionAuthenticationFilter.EXPIRED_REASON);

    assertThatThrownBy(
            () -> interceptor.preHandle(request, new MockHttpServletResponse(), new Object()))
        .isInstanceOf(UnauthenticatedException.class)
        .hasMessage("Your session has expired. Please log in again.");
  }

  @Test
  void anInvalidTokenGetsTheGenericNoLongerValidMessage() {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/auth/session");
    request.setAttribute(
        SessionAuthenticationFilter.AUTH_FAILURE_REASON_ATTRIBUTE,
        SessionAuthenticationFilter.INVALID_REASON);

    assertThatThrownBy(
            () -> interceptor.preHandle(request, new MockHttpServletResponse(), new Object()))
        .isInstanceOf(UnauthenticatedException.class)
        .hasMessage("Your session is no longer valid. Please log in again.");
  }
}
