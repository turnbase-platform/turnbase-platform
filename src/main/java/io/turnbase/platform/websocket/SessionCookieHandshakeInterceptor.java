package io.turnbase.platform.websocket;

import io.turnbase.platform.common.SessionCookies;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

/**
 * The {@code turnbase_session} httpOnly cookie rides the WebSocket upgrade request, not any STOMP
 * frame ({@code websocket.md} § Authentication: "sent automatically by the browser...
 * same-origin"). This interceptor is the only point that ever sees the raw HTTP request, so it
 * stashes the cookie's value into the handshake attributes map — which Spring copies into the STOMP
 * session's own attributes — for {@link WebSocketChannelInterceptor} to read when the {@code
 * CONNECT} frame arrives. It performs no validation itself; an absent or invalid token is simply
 * absent from the attributes, and the {@code CONNECT} interceptor rejects the connection.
 */
class SessionCookieHandshakeInterceptor implements HandshakeInterceptor {

  static final String SESSION_TOKEN_ATTRIBUTE = "turnbase.sessionToken";

  private final String cookieName;

  SessionCookieHandshakeInterceptor(SessionCookies sessionCookies) {
    this.cookieName = sessionCookies.name();
  }

  @Override
  public boolean beforeHandshake(
      ServerHttpRequest request,
      ServerHttpResponse response,
      WebSocketHandler wsHandler,
      Map<String, Object> attributes) {
    if (request instanceof ServletServerHttpRequest servletRequest) {
      String token = cookieValue(servletRequest.getServletRequest());
      if (token != null) {
        attributes.put(SESSION_TOKEN_ATTRIBUTE, token);
      }
    }
    return true;
  }

  @Override
  public void afterHandshake(
      ServerHttpRequest request,
      ServerHttpResponse response,
      WebSocketHandler wsHandler,
      @Nullable Exception exception) {}

  private @Nullable String cookieValue(HttpServletRequest request) {
    Cookie[] cookies = request.getCookies();
    if (cookies == null) {
      return null;
    }
    for (Cookie cookie : cookies) {
      if (cookieName.equals(cookie.getName())) {
        return cookie.getValue();
      }
    }
    return null;
  }
}
