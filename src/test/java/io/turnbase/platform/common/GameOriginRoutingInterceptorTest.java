package io.turnbase.platform.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** {@code game-asset-serving.md} §4/§9 invariant 1 — the {@code Host} filter's binary split. */
class GameOriginRoutingInterceptorTest {

  private final GameOriginRoutingInterceptor interceptor =
      new GameOriginRoutingInterceptor(
          new GameOriginMatcher(
              new GameFrontendProperties("http://{gameId}.games.localhost:8080")));

  @Test
  void aGameHostReachingAnAssetRoutePasses() {
    MockHttpServletRequest request =
        new MockHttpServletRequest("GET", "/g/11111111-1111-1111-1111-111111111111/1.0.0/main.js");
    request.addHeader("Host", "chess.games.localhost:8080");

    assertThat(interceptor.preHandle(request, new MockHttpServletResponse(), new Object()))
        .isTrue();
  }

  @Test
  void aGameHostReachingThePlatformApi404s() {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/games");
    request.addHeader("Host", "chess.games.localhost:8080");

    assertThatThrownBy(
            () -> interceptor.preHandle(request, new MockHttpServletResponse(), new Object()))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void theNonGameHostReachingTheApiPasses() {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/games");
    request.addHeader("Host", "turnbase.localhost:8080");

    assertThat(interceptor.preHandle(request, new MockHttpServletResponse(), new Object()))
        .isTrue();
  }

  @Test
  void theNonGameHostReachingAnAssetRoute404s() {
    MockHttpServletRequest request =
        new MockHttpServletRequest("GET", "/g/11111111-1111-1111-1111-111111111111/1.0.0/main.js");
    request.addHeader("Host", "turnbase.localhost:8080");

    assertThatThrownBy(
            () -> interceptor.preHandle(request, new MockHttpServletResponse(), new Object()))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void anUnrecognizedHostReachingTheApiStillPassesSoTestHarnessesWork() {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/games");
    request.addHeader("Host", "127.0.0.1:54321");

    assertThat(interceptor.preHandle(request, new MockHttpServletResponse(), new Object()))
        .isTrue();
  }
}
