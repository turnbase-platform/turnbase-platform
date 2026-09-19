package io.turnbase.platform.common;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * {@code game-asset-serving.md} §4 and §9 invariant 1: one classification, by {@code Host} alone,
 * decides whether a request may reach the platform API/frontend or {@code /play/**}/{@code /g/**} —
 * never both, regardless of path. A game-origin {@code Host} reaching anything else, or a
 * non-game-origin {@code Host} reaching {@code /play/**}/{@code /g/**}, 404s before the handler
 * (whether or not one is even mapped yet, per §4's table).
 */
@Component
class GameOriginRoutingInterceptor implements HandlerInterceptor {

  private final GameOriginMatcher originMatcher;

  GameOriginRoutingInterceptor(GameOriginMatcher originMatcher) {
    this.originMatcher = originMatcher;
  }

  @Override
  public boolean preHandle(
      HttpServletRequest request, HttpServletResponse response, Object handler) {
    boolean gameRoute =
        request.getRequestURI().startsWith("/play/") || request.getRequestURI().startsWith("/g/");
    boolean gameHost = originMatcher.isGameHost(request.getHeader(HttpHeaders.HOST));
    if (gameHost != gameRoute) {
      throw new NotFoundException("Not found.");
    }
    return true;
  }
}
