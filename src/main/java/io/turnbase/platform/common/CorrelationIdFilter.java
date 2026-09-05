package io.turnbase.platform.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.security.Principal;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The first of observability.md §3's four MDC boundaries: sets {@code requestId} (from {@code
 * X-Request-Id} if present, else generated) and {@code userId} (from the principal, when one
 * exists) for every HTTP request, and clears both afterwards so a thread pooled by the servlet
 * container never leaks one request's correlation IDs into the next.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class CorrelationIdFilter extends OncePerRequestFilter {

  static final String REQUEST_ID_HEADER = "X-Request-Id";
  static final String REQUEST_ID_MDC_KEY = "requestId";
  static final String USER_ID_MDC_KEY = "userId";

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    String requestId = requestIdFrom(request);
    MDC.put(REQUEST_ID_MDC_KEY, requestId);
    response.setHeader(REQUEST_ID_HEADER, requestId);
    try {
      @Nullable Principal principal = request.getUserPrincipal();
      if (principal != null) {
        MDC.put(USER_ID_MDC_KEY, principal.getName());
      }
      filterChain.doFilter(request, response);
    } finally {
      MDC.remove(REQUEST_ID_MDC_KEY);
      MDC.remove(USER_ID_MDC_KEY);
    }
  }

  private static String requestIdFrom(HttpServletRequest request) {
    String header = request.getHeader(REQUEST_ID_HEADER);
    return (header == null || header.isBlank()) ? UUID.randomUUID().toString() : header;
  }
}
