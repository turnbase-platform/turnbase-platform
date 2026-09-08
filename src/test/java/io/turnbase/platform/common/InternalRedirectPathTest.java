package io.turnbase.platform.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** {@code platform-views.md} VW-REDIR-005/006. */
class InternalRedirectPathTest {

  @Test
  void acceptsAValidInternalPath() {
    assertThat(InternalRedirectPath.validateOrFallback("/rooms/abcd", "/rooms"))
        .isEqualTo("/rooms/abcd");
  }

  @Test
  void fallsBackOnAnEmptyCandidate() {
    assertThat(InternalRedirectPath.validateOrFallback("", "/rooms")).isEqualTo("/rooms");
  }

  @Test
  void fallsBackOnAProtocolRelativeUrl() {
    assertThat(InternalRedirectPath.validateOrFallback("//evil.example.com", "/rooms"))
        .isEqualTo("/rooms");
  }

  @Test
  void fallsBackOnAnAbsoluteUrl() {
    assertThat(InternalRedirectPath.validateOrFallback("https://evil.example.com", "/rooms"))
        .isEqualTo("/rooms");
  }

  @Test
  void fallsBackOnAPathNotStartingWithSlash() {
    assertThat(InternalRedirectPath.validateOrFallback("rooms/abcd", "/rooms")).isEqualTo("/rooms");
  }
}
