package io.turnbase.platform.admin;

import io.turnbase.platform.common.ProblemType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.servlet.HandlerInterceptor;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code admin-rest-api.md} §2 — HTTP Basic against the single static credential pair, all or
 * nothing (CON-001, no {@code 403} on this surface). Rejects by writing the response directly
 * rather than throwing: {@link io.turnbase.platform.common.GlobalExceptionHandler} has no way to
 * know this failure needs a {@code WWW-Authenticate} header that no other {@code unauthenticated}
 * producer on the public listener should ever get.
 */
class AdminBasicAuthInterceptor implements HandlerInterceptor {

  private static final String REALM_HEADER_VALUE = "Basic realm=\"turnbase-admin\"";

  private final AdminProperties adminProperties;
  private final JsonMapper objectMapper;

  AdminBasicAuthInterceptor(AdminProperties adminProperties, JsonMapper objectMapper) {
    this.adminProperties = adminProperties;
    this.objectMapper = objectMapper;
  }

  @Override
  public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
      throws IOException {
    if (isAuthorized(request)) {
      return true;
    }
    reject(response);
    return false;
  }

  private boolean isAuthorized(HttpServletRequest request) {
    String header = request.getHeader(HttpHeaders.AUTHORIZATION);
    if (header == null || !header.regionMatches(true, 0, "Basic ", 0, 6)) {
      return false;
    }
    byte[] decoded;
    try {
      decoded = Base64.getDecoder().decode(header.substring(6).trim());
    } catch (IllegalArgumentException e) {
      return false;
    }
    String credentials = new String(decoded, StandardCharsets.UTF_8);
    int separator = credentials.indexOf(':');
    if (separator < 0) {
      return false;
    }
    String username = credentials.substring(0, separator);
    String password = credentials.substring(separator + 1);
    return constantTimeEquals(username, adminProperties.username())
        && constantTimeEquals(password, adminProperties.password());
  }

  private void reject(HttpServletResponse response) throws IOException {
    response.setStatus(ProblemType.UNAUTHENTICATED.status().value());
    response.setHeader(HttpHeaders.WWW_AUTHENTICATE, REALM_HEADER_VALUE);
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    ProblemDetail body =
        ProblemDetail.forStatusAndDetail(
            ProblemType.UNAUTHENTICATED.status(), "Missing or invalid admin credentials.");
    body.setType(ProblemType.UNAUTHENTICATED.typeUri());
    body.setTitle(ProblemType.UNAUTHENTICATED.title());
    objectMapper.writeValue(response.getOutputStream(), body);
  }

  private static boolean constantTimeEquals(String a, String b) {
    return MessageDigest.isEqual(
        a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
  }
}
