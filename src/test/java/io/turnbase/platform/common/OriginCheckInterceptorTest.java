package io.turnbase.platform.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** {@code rest-conventions.md} § Origin Policy and CSRF. */
class OriginCheckInterceptorTest {

  private final OriginCheckInterceptor interceptor =
      new OriginCheckInterceptor(new PlatformOriginProperties("http://turnbase.localhost:8080"));

  @Test
  void allowsAGetRegardlessOfOrigin() {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/users/alice");
    request.addHeader("Origin", "https://evil.example.com");

    assertThat(interceptor.preHandle(request, new MockHttpServletResponse(), new Object()))
        .isTrue();
  }

  @Test
  void allowsAPostWithNoOriginHeader() {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/login");

    assertThat(interceptor.preHandle(request, new MockHttpServletResponse(), new Object()))
        .isTrue();
  }

  @Test
  void allowsAPostFromThePlatformOrigin() {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/login");
    request.addHeader("Origin", "http://turnbase.localhost:8080");

    assertThat(interceptor.preHandle(request, new MockHttpServletResponse(), new Object()))
        .isTrue();
  }

  @Test
  void rejectsAPostFromAForeignOrigin() {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/login");
    request.addHeader("Origin", "https://evil.example.com");

    assertThatThrownBy(
            () -> interceptor.preHandle(request, new MockHttpServletResponse(), new Object()))
        .isInstanceOf(ForbiddenException.class);
  }

  @Test
  void rejectsADeleteFromAForeignOrigin() {
    MockHttpServletRequest request = new MockHttpServletRequest("DELETE", "/api/users/me");
    request.addHeader("Origin", "https://evil.example.com");

    assertThatThrownBy(
            () -> interceptor.preHandle(request, new MockHttpServletResponse(), new Object()))
        .isInstanceOf(ForbiddenException.class);
  }
}
