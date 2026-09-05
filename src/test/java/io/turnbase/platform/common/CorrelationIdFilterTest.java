package io.turnbase.platform.common;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import java.security.Principal;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class CorrelationIdFilterTest {

  private final CorrelationIdFilter filter = new CorrelationIdFilter();

  @Test
  void generatesARequestIdWhenNoneIsSupplied() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    MockHttpServletResponse response = new MockHttpServletResponse();
    AtomicReference<String> seenDuringRequest = new AtomicReference<>();
    FilterChain chain = (req, res) -> seenDuringRequest.set(MDC.get("requestId"));

    filter.doFilter(request, response, chain);

    assertThat(seenDuringRequest.get()).isNotBlank();
    assertThat(response.getHeader("X-Request-Id")).isEqualTo(seenDuringRequest.get());
    assertThat(MDC.get("requestId")).isNull();
  }

  @Test
  void reusesAnIncomingRequestIdHeader() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader("X-Request-Id", "incoming-id");
    MockHttpServletResponse response = new MockHttpServletResponse();
    AtomicReference<String> seenDuringRequest = new AtomicReference<>();
    FilterChain chain = (req, res) -> seenDuringRequest.set(MDC.get("requestId"));

    filter.doFilter(request, response, chain);

    assertThat(seenDuringRequest.get()).isEqualTo("incoming-id");
  }

  @Test
  void omitsUserIdWhenThereIsNoPrincipal() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    MockHttpServletResponse response = new MockHttpServletResponse();
    AtomicReference<String> seenDuringRequest = new AtomicReference<>();
    FilterChain chain = (req, res) -> seenDuringRequest.set(MDC.get("userId"));

    filter.doFilter(request, response, chain);

    assertThat(seenDuringRequest.get()).isNull();
  }

  @Test
  void setsUserIdFromThePrincipalWhenPresent() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setUserPrincipal((Principal) () -> "user-42");
    MockHttpServletResponse response = new MockHttpServletResponse();
    AtomicReference<String> seenDuringRequest = new AtomicReference<>();
    FilterChain chain = (req, res) -> seenDuringRequest.set(MDC.get("userId"));

    filter.doFilter(request, response, chain);

    assertThat(seenDuringRequest.get()).isEqualTo("user-42");
    assertThat(MDC.get("userId")).isNull();
  }
}
