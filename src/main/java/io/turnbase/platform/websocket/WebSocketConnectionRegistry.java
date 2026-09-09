package io.turnbase.platform.websocket;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.turnbase.platform.common.AuthenticatedUser;
import io.turnbase.platform.session.SessionRevokedEvent;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

/**
 * Every live STOMP session, keyed by its session id — the mechanism behind three of this item's
 * Done-when clauses: the connection cap ({@code rate-limiting.md} § Tier 6), the {@code
 * turnbase_websocket_connections_active} gauge ({@code observability.md} §2, {@code
 * platform-operations.md} FR-MET-002), and closing every connection a revoked {@code
 * session.SessionRevokedEvent} names (`FR-SESS-010`, `IMP-GATE-28`). Room-specific socket-severing
 * (`room.RoomOwnershipLostEvent`) is {@code room}'s own concern — this class exposes {@link
 * #closeSession} as the primitive it calls, and knows nothing about rooms itself.
 */
@Component
public class WebSocketConnectionRegistry {

  private final Map<String, AuthenticatedUser> connections = new ConcurrentHashMap<>();
  private final Map<UUID, Set<String>> sessionIdsByUser = new ConcurrentHashMap<>();
  private final MessageChannel clientOutboundChannel;
  private final ObjectMapper objectMapper;

  /**
   * {@code clientOutboundChannel} is {@code @Lazy}: eagerly resolving it here would force Spring to
   * finish building the whole STOMP broker relay — which itself depends on every {@code
   * WebSocketMessageBrokerConfigurer} bean, including {@link WebSocketConfig}, which depends on
   * {@link WebSocketChannelInterceptor}, which depends on this class — before this bean can finish
   * constructing. A lazy proxy defers that resolution to the first actual {@link #closeSession}
   * call, by which point the broker is long since initialized.
   */
  WebSocketConnectionRegistry(
      @Qualifier("clientOutboundChannel") @Lazy MessageChannel clientOutboundChannel,
      ObjectMapper objectMapper,
      MeterRegistry meterRegistry) {
    this.clientOutboundChannel = clientOutboundChannel;
    this.objectMapper = objectMapper;
    Gauge.builder("turnbase_websocket_connections_active", connections, Map::size)
        .register(meterRegistry);
  }

  /**
   * Atomically checks the per-user connection cap and registers {@code stompSessionId} if under it.
   * {@code rate-limiting.md} § Tier 6: "Maximum 4 concurrent WebSocket connections per user."
   */
  boolean tryRegister(String stompSessionId, AuthenticatedUser user, int maxConnectionsPerUser) {
    Set<String> userSessions =
        sessionIdsByUser.computeIfAbsent(user.userId(), id -> ConcurrentHashMap.newKeySet());
    synchronized (userSessions) {
      if (userSessions.size() >= maxConnectionsPerUser) {
        return false;
      }
      userSessions.add(stompSessionId);
    }
    connections.put(stompSessionId, user);
    return true;
  }

  void unregister(String stompSessionId) {
    @Nullable AuthenticatedUser user = connections.remove(stompSessionId);
    if (user != null) {
      Set<String> userSessions = sessionIdsByUser.get(user.userId());
      if (userSessions != null) {
        userSessions.remove(stompSessionId);
      }
    }
  }

  @Nullable AuthenticatedUser userOf(String stompSessionId) {
    return connections.get(stompSessionId);
  }

  int activeConnectionCount() {
    return connections.size();
  }

  /**
   * {@code FR-SESS-010}: every connection registered under the revoked session gets a STOMP {@code
   * ERROR} frame naming {@code revoked_reason}, then is closed. A connection is scoped by the login
   * session ({@code AuthenticatedUser.sessionId()}), not by the room or game it happens to be in.
   */
  @EventListener
  void onSessionRevoked(SessionRevokedEvent event) {
    connections.entrySet().stream()
        .filter(entry -> entry.getValue().sessionId().equals(event.sessionId()))
        .map(Map.Entry::getKey)
        .toList()
        .forEach(
            stompSessionId ->
                closeSession(
                    stompSessionId,
                    "SESSION_REVOKED",
                    "Your session was ended: " + event.reason()));
  }

  /**
   * The force-close primitive: sends a STOMP {@code ERROR} command frame carrying {@code {code,
   * message}} as its JSON body, addressed to {@code stompSessionId} via the {@code
   * clientOutboundChannel}. Spring's {@code StompSubProtocolHandler} closes the underlying
   * WebSocket session immediately after relaying an outbound {@code ERROR} frame — this is the one
   * documented mechanism for a server to close a specific STOMP session it did not itself accept a
   * disconnect from.
   */
  public void closeSession(String stompSessionId, String code, String message) {
    unregister(stompSessionId);
    StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.ERROR);
    accessor.setSessionId(stompSessionId);
    accessor.setMessage(message);
    accessor.setLeaveMutable(true);
    byte[] body = errorBody(code, message);
    Message<byte[]> errorMessage = MessageBuilder.createMessage(body, accessor.getMessageHeaders());
    clientOutboundChannel.send(errorMessage);
  }

  private byte[] errorBody(String code, String message) {
    try {
      return objectMapper
          .writeValueAsString(
              Map.of("type", "ERROR", "payload", Map.of("code", code, "message", message)))
          .getBytes(StandardCharsets.UTF_8);
    } catch (JsonProcessingException e) {
      return message.getBytes(StandardCharsets.UTF_8);
    }
  }
}
