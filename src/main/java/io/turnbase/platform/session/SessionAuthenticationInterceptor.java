package io.turnbase.platform.session;

import io.turnbase.platform.common.UnauthenticatedException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * {@code rest-conventions.md} § Authentication / {@code user-accounts.md} NFR-SEC-006: every
 * endpoint requires a valid session unless explicitly exempt. {@link SessionAuthenticationFilter}
 * has already resolved (or failed to resolve) a principal by the time {@link #preHandle} runs; this
 * class only decides whether the absence of one is acceptable for the path being requested.
 *
 * <p>{@link #PUBLIC_PATHS} is NFR-SEC-006's exempt list, as far as it has server-side routes today
 * — registration and login. The requirement also exempts the landing page, user profile pages, the
 * game catalog and game detail pages, none of which have a backend route yet; the item that adds
 * one adds its path here.
 *
 * <p>{@code /test/} is a second, unrelated exemption: {@code GlobalExceptionHandlerIT}'s harness
 * controller lives only on the test classpath (never shipped, {@code IMP-M1-01}) and exercises
 * {@code GlobalExceptionHandler} directly — NFR-SEC-006 governs platform endpoints, and this isn't
 * one.
 */
class SessionAuthenticationInterceptor implements HandlerInterceptor {

  private static final Set<String> PUBLIC_PATHS = Set.of("/api/auth/register", "/api/auth/login");
  private static final String TEST_HARNESS_PREFIX = "/test/";

  @Override
  public boolean preHandle(
      HttpServletRequest request, HttpServletResponse response, Object handler) {
    if (request.getUserPrincipal() != null) {
      return true;
    }
    String path = pathOf(request);
    if (PUBLIC_PATHS.contains(path) || path.startsWith(TEST_HARNESS_PREFIX)) {
      return true;
    }
    throw new UnauthenticatedException(detailFor(failureReasonOf(request)));
  }

  private static String pathOf(HttpServletRequest request) {
    return request.getRequestURI().substring(request.getContextPath().length());
  }

  private static @Nullable String failureReasonOf(HttpServletRequest request) {
    Object reason = request.getAttribute(SessionAuthenticationFilter.AUTH_FAILURE_REASON_ATTRIBUTE);
    return reason == null ? null : reason.toString();
  }

  /**
   * {@code user-accounts.md} FR-SESS-004's "clear error response" — displacement names its cause.
   */
  private static String detailFor(@Nullable String reason) {
    if (reason == null) {
      return "Authentication is required for this endpoint.";
    }
    return switch (reason) {
      case SessionService.DISPLACED_REASON ->
          "Your session was ended because you logged in from another device.";
      case SessionAuthenticationFilter.EXPIRED_REASON ->
          "Your session has expired. Please log in again.";
      default -> "Your session is no longer valid. Please log in again.";
    };
  }
}
