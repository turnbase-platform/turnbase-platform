package io.turnbase.platform.websocket;

/**
 * Thrown from the {@code CONNECT}-frame interceptor to reject a connection — an unauthenticated
 * cookie, an active reconnect lockout, or the connection cap. Spring's STOMP handling converts any
 * exception raised while processing an inbound frame into a STOMP {@code ERROR} frame followed by
 * closing the session ({@code websocket.md} § Authentication: "rejected with a STOMP ERROR frame
 * and the connection is closed"), so this class carries only the player-safe message that becomes
 * that frame's text.
 */
class WebSocketAuthenticationException extends RuntimeException {

  WebSocketAuthenticationException(String message) {
    super(message);
  }
}
