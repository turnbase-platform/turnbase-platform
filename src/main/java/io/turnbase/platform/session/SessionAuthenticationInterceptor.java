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
 * — registration, login and guest creation (IMP-GATE-29 added the last of these to NFR-SEC-006
 * itself; a caller minting a guest account has no session yet, so the endpoint cannot require one),
 * and the five Google OAuth routes ({@code oauth-rest-api.md}, IMP-GATE-38): the entry point and
 * callback never have a session either, and the three pending-registration JSON endpoints are gated
 * by {@code oauth-rest-api.md} §2's pending token instead — a different mechanism this class does
 * not enforce. {@code GET /api/users/{username}} (user profile pages, IMP-M1-07) is exempt too, but
 * is matched by {@link #isPublicProfileRead}, not this set — the path carries a variable segment.
 * The requirement also exempts the landing page, the game catalog and game detail pages, none of
 * which have a backend route yet; the item that adds one adds its own exemption here.
 *
 * <p>{@code /test/} is a second, unrelated exemption: {@code GlobalExceptionHandlerIT}'s harness
 * controller lives only on the test classpath (never shipped, {@code IMP-M1-01}) and exercises
 * {@code GlobalExceptionHandler} directly — NFR-SEC-006 governs platform endpoints, and this isn't
 * one.
 */
class SessionAuthenticationInterceptor implements HandlerInterceptor {

  private static final Set<String> PUBLIC_PATHS =
      Set.of(
          "/api/auth/register",
          "/api/auth/login",
          "/api/auth/guest",
          "/auth/google",
          "/auth/callback",
          "/api/auth/oauth/pending",
          "/api/auth/oauth/complete-registration",
          "/api/auth/oauth/link-challenge");
  private static final String TEST_HARNESS_PREFIX = "/test/";
  private static final String USERS_PATH_PREFIX = "/api/users/";

  @Override
  public boolean preHandle(
      HttpServletRequest request, HttpServletResponse response, Object handler) {
    if (request.getUserPrincipal() != null) {
      return true;
    }
    String path = pathOf(request);
    if (PUBLIC_PATHS.contains(path)
        || path.startsWith(TEST_HARNESS_PREFIX)
        || isPublicProfileRead(request, path)) {
      return true;
    }
    throw new UnauthenticatedException(detailFor(failureReasonOf(request)));
  }

  /**
   * {@code user-accounts.md} NFR-SEC-006's "user profile pages" and {@code rest-conventions.md}'s
   * {@code GET /api/users/{username}} (read) row — public, subject to the privacy resolution the
   * handler itself performs. {@code social-rest-api.md} §7's {@code GET
   * /api/users/{username}/friends} is the same kind of read and carries the identical exemption.
   * {@code /api/users/me/**} is excluded: it is never a real username (registered and guest
   * usernames are both at least 3 characters), but excluding it explicitly keeps this exemption
   * from ever depending on that being true — including {@code /api/users/me/friends/{userId}}
   * (`IMP-M1-09`'s unfriend), which is session-required, not a profile read.
   */
  private static boolean isPublicProfileRead(HttpServletRequest request, String path) {
    if (!"GET".equalsIgnoreCase(request.getMethod()) || !path.startsWith(USERS_PATH_PREFIX)) {
      return false;
    }
    String rest = path.substring(USERS_PATH_PREFIX.length());
    if (rest.isEmpty() || "me".equals(rest) || rest.startsWith("me/")) {
      return false;
    }
    return !rest.contains("/") || rest.endsWith("/friends");
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
