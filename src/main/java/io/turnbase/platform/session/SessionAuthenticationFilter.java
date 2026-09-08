package io.turnbase.platform.session;

import io.turnbase.platform.common.AuthenticatedUser;
import io.turnbase.platform.common.SessionCookie;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.security.Principal;
import java.time.Instant;
import java.util.Optional;
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

  static final String EXPIRED_REASON = "expired";
  static final String INVALID_REASON = "invalid";

  private final JwtService jwtService;
  private final UserSessionRepository sessions;

  SessionAuthenticationFilter(JwtService jwtService, UserSessionRepository sessions) {
    this.jwtService = jwtService;
    this.sessions = sessions;
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

    JwtClaims claims;
    try {
      claims = jwtService.verify(token);
    } catch (JwtVerificationException e) {
      request.setAttribute(AUTH_FAILURE_REASON_ATTRIBUTE, INVALID_REASON);
      filterChain.doFilter(request, response);
      return;
    }

    Optional<UserSession> maybeSession = sessions.findById(claims.sessionId());
    if (maybeSession.isEmpty()) {
      request.setAttribute(AUTH_FAILURE_REASON_ATTRIBUTE, INVALID_REASON);
      filterChain.doFilter(request, response);
      return;
    }

    UserSession session = maybeSession.get();
    if (session.revokedAt() != null) {
      request.setAttribute(AUTH_FAILURE_REASON_ATTRIBUTE, session.revokedReason());
      filterChain.doFilter(request, response);
      return;
    }
    if (!session.expiresAt().isAfter(Instant.now())) {
      request.setAttribute(AUTH_FAILURE_REASON_ATTRIBUTE, EXPIRED_REASON);
      filterChain.doFilter(request, response);
      return;
    }

    AuthenticatedUser principal = new AuthenticatedUser(session.userId(), session.sessionId());
    filterChain.doFilter(new PrincipalRequestWrapper(request, principal), response);
  }

  private static @Nullable String cookieValue(HttpServletRequest request) {
    Cookie[] cookies = request.getCookies();
    if (cookies == null) {
      return null;
    }
    for (Cookie cookie : cookies) {
      if (SessionCookie.NAME.equals(cookie.getName())) {
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
