package io.turnbase.platform.common;

import java.net.URI;
import org.springframework.http.HttpStatus;

/**
 * The closed error-type taxonomy from {@code error-handling.md} § Error Type Taxonomy. A new
 * failure mode is registered here first — it does not get a {@code type} URI invented at the call
 * site.
 */
public enum ProblemType {
  VALIDATION_FAILED("validation-failed", "Validation Failed", HttpStatus.BAD_REQUEST),
  UNAUTHENTICATED("unauthenticated", "Unauthenticated", HttpStatus.UNAUTHORIZED),
  FORBIDDEN("forbidden", "Forbidden", HttpStatus.FORBIDDEN),
  NOT_FOUND("not-found", "Not Found", HttpStatus.NOT_FOUND),
  CONFLICT("conflict", "Conflict", HttpStatus.CONFLICT),
  GONE("gone", "Gone", HttpStatus.GONE),
  PAYLOAD_TOO_LARGE("payload-too-large", "Payload Too Large", HttpStatus.CONTENT_TOO_LARGE),
  BUSINESS_RULE_VIOLATION(
      "business-rule-violation", "Business Rule Violation", HttpStatus.UNPROCESSABLE_CONTENT),
  RATE_LIMIT_EXCEEDED("rate-limit-exceeded", "Too Many Requests", HttpStatus.TOO_MANY_REQUESTS),
  INTERNAL_ERROR("internal-error", "Internal Server Error", HttpStatus.INTERNAL_SERVER_ERROR),
  SERVICE_UNAVAILABLE("service-unavailable", "Service Unavailable", HttpStatus.SERVICE_UNAVAILABLE),
  ROOM_UNAVAILABLE_RETRY(
      "room-unavailable-retry", "Room Unavailable", HttpStatus.SERVICE_UNAVAILABLE);

  private static final String BASE_URI = "https://turnbase.io/errors/";

  private final String slug;
  private final String title;
  private final HttpStatus status;

  ProblemType(String slug, String title, HttpStatus status) {
    this.slug = slug;
    this.title = title;
    this.status = status;
  }

  public URI typeUri() {
    return URI.create(BASE_URI + slug);
  }

  public String title() {
    return title;
  }

  public HttpStatus status() {
    return status;
  }
}
