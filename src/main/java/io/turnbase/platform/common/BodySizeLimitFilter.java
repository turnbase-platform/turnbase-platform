package io.turnbase.platform.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import org.jspecify.annotations.Nullable;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * {@code input-validation.md} § Size limits for a body whose size is not declared. {@link
 * RequestSizeInterceptor} can only compare {@code Content-Length}, which a {@code
 * Transfer-Encoding: chunked} request does not carry, so for such a request this filter wraps the
 * input stream and throws {@link PayloadTooLargeException} on the byte that crosses {@link
 * HttpProperties#maxBodySize()}. The exception surfaces from the handler's body read and reaches
 * {@link GlobalExceptionHandler} as the same {@code 413} problem document.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class BodySizeLimitFilter extends OncePerRequestFilter {

  private final long maxBytes;
  private final String maxBodySize;

  public BodySizeLimitFilter(HttpProperties httpProperties) {
    this.maxBytes = httpProperties.maxBodySize().toBytes();
    this.maxBodySize = httpProperties.maxBodySize().toString();
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    String path = request.getRequestURI();
    return request.getContentLengthLong() >= 0
        || path.startsWith("/admin/")
        || path.startsWith("/turnbase.gamehost.");
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    filterChain.doFilter(new LimitedRequest(request, maxBytes, maxBodySize), response);
  }

  private static final class LimitedRequest extends HttpServletRequestWrapper {

    private final long maxBytes;
    private final String maxBodySize;
    private @Nullable LimitedStream stream;

    LimitedRequest(HttpServletRequest request, long maxBytes, String maxBodySize) {
      super(request);
      this.maxBytes = maxBytes;
      this.maxBodySize = maxBodySize;
    }

    @Override
    public ServletInputStream getInputStream() throws IOException {
      if (stream == null) {
        stream = new LimitedStream(super.getInputStream(), maxBytes, maxBodySize);
      }
      return stream;
    }

    @Override
    public BufferedReader getReader() throws IOException {
      String encoding = getCharacterEncoding();
      Charset charset = encoding == null ? StandardCharsets.ISO_8859_1 : Charset.forName(encoding);
      return new BufferedReader(new InputStreamReader(getInputStream(), charset));
    }
  }

  private static final class LimitedStream extends ServletInputStream {

    private final ServletInputStream delegate;
    private final long maxBytes;
    private final String maxBodySize;
    private long count;

    LimitedStream(ServletInputStream delegate, long maxBytes, String maxBodySize) {
      this.delegate = delegate;
      this.maxBytes = maxBytes;
      this.maxBodySize = maxBodySize;
    }

    private void account(long read) {
      if (read > 0) {
        count += read;
        if (count > maxBytes) {
          throw new PayloadTooLargeException(
              "The request body exceeds the maximum size of " + maxBodySize + ".");
        }
      }
    }

    @Override
    public int read() throws IOException {
      int b = delegate.read();
      account(b < 0 ? 0 : 1);
      return b;
    }

    @Override
    public int read(byte[] buffer, int off, int len) throws IOException {
      int n = delegate.read(buffer, off, len);
      account(n);
      return n;
    }

    @Override
    public boolean isFinished() {
      return delegate.isFinished();
    }

    @Override
    public boolean isReady() {
      return delegate.isReady();
    }

    @Override
    public void setReadListener(ReadListener listener) {
      delegate.setReadListener(listener);
    }
  }
}
