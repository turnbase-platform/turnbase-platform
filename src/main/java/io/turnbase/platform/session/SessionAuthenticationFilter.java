package io.turnbase.platform.session;

import io.turnbase.platform.common.AuthenticatedUser;
import io.turnbase.platform.common.SessionCookies;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.security.Principal;
import org.jspecify.annotations.Nullable;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Resolves the {@code turnbase_session} cookie (rest-conventions.md § Authentication) against
 * {@code user_session} on every request. This filter never rejects a request itself — it only
 * establishes {@link HttpServletRequest#getUserPrincipal()} when the token is valid, or records why
 * it could not be on the {@link #AUTH_FAILURE_REASON_ATTRIBUTE} request attribute. Enforcement (a
 * 401 for a protected path with no principal) is {@link SessionAuthenticationInterceptor}'s job — a
 * {@code HandlerInterceptor} runs inside {@code DispatcherServlet}'s own exception resolution, so a
 * rejection there reaches {@code GlobalExceptionHandler} the same way every other domain failure
 * does; a {@code Filter} that threw would bypass it entirely.
 *
 * <p>Ordered ahead of {@code CorrelationIdFilter} ({@code HIGHEST_PRECEDENCE + 10}) so the
 * correlation filter's {@code userId} MDC value, read from {@code getUserPrincipal()}, sees this
 * filter's result rather than running before it exists.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SessionAuthenticationFilter extends OncePerRequestFilter {

  /**
   * The value is one of {@code user_session.revoked_reason} (e.g. {@code DISPLACED}, {@code
   * LOGOUT}), or {@code "expired"} / {@code "invalid"} for a token this item's own checks reject
   * before a reason column is even consulted.
   */
  static final String AUTH_FAILURE_REASON_ATTRIBUTE =
      "io.turnbase.platform.session.authFailureReason";

  static final String EXPIRED_REASON = SessionService.EXPIRED_REASON;
  static final String INVALID_REASON = SessionService.INVALID_REASON;

  private final SessionService sessionService;
  private final String cookieName;

  SessionAuthenticationFilter(SessionService sessionService, SessionCookies sessionCookies) {
    this.sessionService = sessionService;
    this.cookieName = sessionCookies.name();
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    String token = cookieValue(request);
    if (token == null) {
      filterChain.doFilter(request, response);
      return;
    }

    switch (sessionService.resolve(token)) {
      case SessionService.Resolution.Valid valid ->
          filterChain.doFilter(new PrincipalRequestWrapper(request, valid.user()), response);
      case SessionService.Resolution.Invalid invalid -> {
        request.setAttribute(AUTH_FAILURE_REASON_ATTRIBUTE, invalid.reason());
        filterChain.doFilter(request, response);
      }
    }
  }

  private @Nullable String cookieValue(HttpServletRequest request) {
    Cookie[] cookies = request.getCookies();
    if (cookies == null) {
      return null;
    }
    for (Cookie cookie : cookies) {
      if (cookieName.equals(cookie.getName())) {
        return cookie.getValue();
      }
    }
    return null;
  }

  private static final class PrincipalRequestWrapper extends HttpServletRequestWrapper {
    private final AuthenticatedUser principal;

    PrincipalRequestWrapper(HttpServletRequest request, AuthenticatedUser principal) {
      super(request);
      this.principal = principal;
    }

    @Override
    public Principal getUserPrincipal() {
      return principal;
    }
  }
}
