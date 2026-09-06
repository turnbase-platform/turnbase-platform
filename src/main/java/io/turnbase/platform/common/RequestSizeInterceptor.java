package io.turnbase.platform.common;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.util.unit.DataSize;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * {@code input-validation.md} § The pipeline, step 1 ("Size — reject oversized bodies/frames before
 * allocating a parse buffer") and § Size limits' 64 KB HTTP JSON body cap ({@link
 * HttpProperties#maxBodySize()}). Runs as a {@code HandlerInterceptor} rather than a {@code Filter}
 * specifically so a rejection reaches {@link GlobalExceptionHandler} through the same
 * exception-resolution path as every other failure class, keeping the "one advice, one shape" claim
 * literal rather than aspirational.
 */
public class RequestSizeInterceptor implements HandlerInterceptor {

  private final DataSize maxBodySize;

  public RequestSizeInterceptor(HttpProperties httpProperties) {
    this.maxBodySize = httpProperties.maxBodySize();
  }

  @Override
  public boolean preHandle(
      HttpServletRequest request, HttpServletResponse response, Object handler) {
    long contentLength = request.getContentLengthLong();
    if (contentLength > maxBodySize.toBytes()) {
      throw new PayloadTooLargeException(
          "The request body exceeds the maximum size of " + maxBodySize + ".");
    }
    return true;
  }
}
