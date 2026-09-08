package io.turnbase.platform.common;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * {@code rest-conventions.md} § Origin Policy and CSRF: "Any POST, PUT, PATCH or DELETE whose
 * {@code Origin} header is present and is not the platform origin is rejected with 403, before
 * authentication." Registered globally ({@code PlatformWebMvcConfig}) rather than per-endpoint,
 * because the rule is stated once, for every state-changing request in the app, not per feature.
 *
 * <p>A request with no {@code Origin} header at all passes here unchanged — that is the documented
 * shape; this check exists to reject a browser's own cross-origin credentialed request, which
 * always carries the header, not to require one from every caller.
 */
@Component
public class OriginCheckInterceptor implements HandlerInterceptor {

  private static final Set<String> STATE_CHANGING_METHODS =
      Set.of("POST", "PUT", "PATCH", "DELETE");

  private final PlatformOriginProperties platformOrigin;

  public OriginCheckInterceptor(PlatformOriginProperties platformOrigin) {
    this.platformOrigin = platformOrigin;
  }

  @Override
  public boolean preHandle(
      HttpServletRequest request, HttpServletResponse response, Object handler) {
    if (!STATE_CHANGING_METHODS.contains(request.getMethod())) {
      return true;
    }
    String origin = request.getHeader("Origin");
    if (origin != null && !platformOrigin.origin().equals(origin)) {
      throw new ForbiddenException("Cross-origin request rejected.");
    }
    return true;
  }
}
