package io.turnbase.platform.common;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import java.net.URI;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * The single producer of {@code error-handling.md}'s RFC 9457 Problem Detail shape, for every class
 * in the closed taxonomy. Every later endpoint conforms to what this class builds — it is why
 * {@code IMP-M1-01} is built before any endpoint.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  @ExceptionHandler(DomainException.class)
  public ResponseEntity<ProblemDetail> handleDomainException(
      DomainException ex, HttpServletRequest request) {
    ProblemDetail body = problemDetail(ex.problemType(), ex.detail(), request.getRequestURI());
    HttpHeaders headers = new HttpHeaders();

    switch (ex) {
      case RoomUnavailableRetryException retry -> {
        body.setProperty("retryAfterMs", retry.retryAfterMs());
        headers.set(
            HttpHeaders.RETRY_AFTER, String.valueOf(toRetryAfterSeconds(retry.retryAfterMs())));
      }
      case RateLimitExceededException rateLimit ->
          headers.set(HttpHeaders.RETRY_AFTER, String.valueOf(rateLimit.retryAfterSeconds()));
      case BusinessRuleException businessRule when !businessRule.checks().isEmpty() ->
          body.setProperty("errors", businessRule.checks());
      case ValidationFailedException validationFailed when !validationFailed.errors().isEmpty() ->
          body.setProperty("errors", validationFailed.errors());
      default -> {
        // No extension members for this failure class.
      }
    }

    return ResponseEntity.status(ex.problemType().status()).headers(headers).body(body);
  }

  @ExceptionHandler(ConstraintViolationException.class)
  public ResponseEntity<ProblemDetail> handleConstraintViolation(
      ConstraintViolationException ex, HttpServletRequest request) {
    List<FieldValidationError> errors =
        ex.getConstraintViolations().stream().map(this::toFieldError).toList();
    ProblemDetail body =
        problemDetail(
            ProblemType.VALIDATION_FAILED,
            "One or more fields failed validation.",
            request.getRequestURI());
    body.setProperty("errors", errors);
    return ResponseEntity.status(ProblemType.VALIDATION_FAILED.status()).body(body);
  }

  @Override
  protected ResponseEntity<Object> handleMethodArgumentNotValid(
      MethodArgumentNotValidException ex,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    List<FieldValidationError> errors =
        ex.getBindingResult().getFieldErrors().stream()
            .map(
                fieldError ->
                    new FieldValidationError(
                        fieldError.getField(), String.valueOf(fieldError.getDefaultMessage())))
            .toList();
    ProblemDetail body =
        problemDetail(
            ProblemType.VALIDATION_FAILED,
            "One or more fields failed validation.",
            instancePath(request));
    body.setProperty("errors", errors);
    return ResponseEntity.status(ProblemType.VALIDATION_FAILED.status()).body(body);
  }

  @Override
  protected ResponseEntity<Object> handleHttpMessageNotReadable(
      HttpMessageNotReadableException ex,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    ProblemDetail body =
        problemDetail(
            ProblemType.VALIDATION_FAILED, "The request body is malformed.", instancePath(request));
    return ResponseEntity.status(ProblemType.VALIDATION_FAILED.status()).body(body);
  }

  @Override
  protected ResponseEntity<Object> handleHandlerMethodValidationException(
      HandlerMethodValidationException ex,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    List<FieldValidationError> errors =
        ex.getParameterValidationResults().stream()
            .flatMap(
                result ->
                    result.getResolvableErrors().stream()
                        .map(
                            error ->
                                new FieldValidationError(
                                    fieldNameOf(result),
                                    String.valueOf(error.getDefaultMessage()))))
            .toList();
    ProblemDetail body =
        problemDetail(
            ProblemType.VALIDATION_FAILED,
            "One or more fields failed validation.",
            instancePath(request));
    body.setProperty("errors", errors);
    return ResponseEntity.status(ProblemType.VALIDATION_FAILED.status()).body(body);
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ProblemDetail> handleUnexpected(Exception ex, HttpServletRequest request) {
    log.error(
        "Unhandled exception processing {} {}", request.getMethod(), request.getRequestURI(), ex);
    ProblemDetail body =
        problemDetail(
            ProblemType.INTERNAL_ERROR, "An unexpected error occurred.", request.getRequestURI());
    return ResponseEntity.status(ProblemType.INTERNAL_ERROR.status()).body(body);
  }

  private FieldValidationError toFieldError(ConstraintViolation<?> violation) {
    String path = violation.getPropertyPath().toString();
    String field = path.contains(".") ? path.substring(path.lastIndexOf('.') + 1) : path;
    return new FieldValidationError(field, violation.getMessage());
  }

  private static String fieldNameOf(ParameterValidationResult result) {
    String name = result.getMethodParameter().getParameterName();
    return name != null ? name : "arg" + result.getMethodParameter().getParameterIndex();
  }

  private static long toRetryAfterSeconds(long retryAfterMs) {
    return Math.ceilDiv(retryAfterMs, 1000);
  }

  private static String instancePath(WebRequest request) {
    if (request instanceof ServletWebRequest servletWebRequest) {
      return servletWebRequest.getRequest().getRequestURI();
    }
    return request.getDescription(false);
  }

  private static ProblemDetail problemDetail(ProblemType type, String detail, String instance) {
    ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(type.status(), detail);
    problemDetail.setType(type.typeUri());
    problemDetail.setTitle(type.title());
    problemDetail.setInstance(URI.create(instance));
    return problemDetail;
  }
}
