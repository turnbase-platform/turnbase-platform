package io.turnbase.platform.websocket;

import io.turnbase.platform.common.AuthenticatedUser;
import io.turnbase.platform.session.SessionService;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

/**
 * The single {@code clientInboundChannel} interceptor: {@code CONNECT}-frame authentication (once,
 * never re-checked per frame — {@code websocket.md} § Authentication, {@code user-accounts.md}
 * FR-SESS-010), the connection cap and reconnect lockout, Tier 6 rate limiting on every frame after
 * {@code CONNECT}, and dispatch to whichever {@link SubscriptionAuthorizer} governs a {@code
 * SUBSCRIBE} destination. Knows nothing about rooms or games — {@link SubscriptionAuthorizer} is
 * the seam for that.
 */
@Component
class WebSocketChannelInterceptor implements ChannelInterceptor {

  private final SessionService sessionService;
  private final WebSocketConnectionRegistry registry;
  private final WebSocketRateLimiter rateLimiter;
  private final WebSocketRateLimitProperties properties;
  private final List<SubscriptionAuthorizer> subscriptionAuthorizers;
  private final SimpMessagingTemplate messagingTemplate;

  WebSocketChannelInterceptor(
      SessionService sessionService,
      WebSocketConnectionRegistry registry,
      WebSocketRateLimiter rateLimiter,
      WebSocketRateLimitProperties properties,
      List<SubscriptionAuthorizer> subscriptionAuthorizers,
      SimpMessagingTemplate messagingTemplate) {
    this.sessionService = sessionService;
    this.registry = registry;
    this.rateLimiter = rateLimiter;
    this.properties = properties;
    this.subscriptionAuthorizers = subscriptionAuthorizers;
    this.messagingTemplate = messagingTemplate;
  }

  @Override
  public @Nullable Message<?> preSend(Message<?> message, MessageChannel channel) {
    StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);
    StompCommand command = accessor.getCommand();
    if (command == StompCommand.CONNECT) {
      return handleConnect(message, accessor);
    }
    if (command == StompCommand.DISCONNECT) {
      registry.unregister(requireSessionId(accessor));
      return message;
    }

    AuthenticatedUser user = principalOf(accessor);
    String stompSessionId = requireSessionId(accessor);

    // Tier 6 first — cheap, classified from headers alone — before any per-destination
    // authorization that might do real work (room's subscribe-time ownership re-verification
    // retries against Redis). rate-limiting.md's own cost rationale is exactly this ordering.
    WebSocketRateLimiter.FrameDecision decision =
        rateLimiter.checkFrame(user, stompSessionId, command, accessor.getDestination());
    Message<?> rateLimited =
        switch (decision) {
          case WebSocketRateLimiter.FrameDecision.Allow ignored -> null;
          case WebSocketRateLimiter.FrameDecision.Drop drop -> {
            if (drop.shouldNotify()) {
              sendError(
                  user,
                  stompSessionId,
                  "RATE_LIMIT_EXCEEDED",
                  "You are sending messages too quickly.",
                  Map.of("bucket", drop.bucket(), "retryAfterMs", drop.retryAfterMs()));
            }
            yield DROPPED;
          }
          case WebSocketRateLimiter.FrameDecision.ForceClose forceClose -> {
            registry.closeSession(
                stompSessionId,
                forceClose.code(),
                "Too many rate-limit violations on this connection.");
            yield DROPPED;
          }
        };
    if (rateLimited != null) {
      return null;
    }

    if (command == StompCommand.SUBSCRIBE) {
      Message<?> dropped = enforceSubscriptionAuthorization(user, stompSessionId, accessor);
      if (dropped != null) {
        return null;
      }
    }
    return message;
  }

  private @Nullable Message<?> handleConnect(Message<?> message, StompHeaderAccessor accessor) {
    String stompSessionId = requireSessionId(accessor);
    Map<String, Object> sessionAttributes = accessor.getSessionAttributes();
    Object token =
        sessionAttributes == null
            ? null
            : sessionAttributes.get(SessionCookieHandshakeInterceptor.SESSION_TOKEN_ATTRIBUTE);
    if (!(token instanceof String tokenString)) {
      throw new WebSocketAuthenticationException("Authentication is required for this connection.");
    }

    SessionService.Resolution resolution = sessionService.resolve(tokenString);
    if (!(resolution instanceof SessionService.Resolution.Valid valid)) {
      throw new WebSocketAuthenticationException("Your session is no longer valid.");
    }
    AuthenticatedUser user = valid.user();

    if (rateLimiter.isLockedOut(user.userId())) {
      throw new WebSocketAuthenticationException(
          "Too many rate-limit violations. Please try again later.");
    }
    if (!registry.tryRegister(stompSessionId, user, properties.maxConnectionsPerUser())) {
      throw new WebSocketAuthenticationException(
          "Too many concurrent connections for this account.");
    }

    accessor.setUser(user);
    accessor.setLeaveMutable(true);
    return MessageBuilder.createMessage(message.getPayload(), accessor.getMessageHeaders());
  }

  /** Returns a non-null sentinel when the subscription was rejected and must be dropped. */
  private @Nullable Message<?> enforceSubscriptionAuthorization(
      AuthenticatedUser user, String stompSessionId, StompHeaderAccessor accessor) {
    String destination = accessor.getDestination();
    if (destination == null) {
      return null;
    }
    for (SubscriptionAuthorizer authorizer : subscriptionAuthorizers) {
      if (!authorizer.supports(destination)) {
        continue;
      }
      SubscriptionDecision decision = authorizer.authorize(user, destination, stompSessionId);
      return switch (decision) {
        case SubscriptionDecision.Allow ignored -> null;
        case SubscriptionDecision.SilentlyDrop ignored -> DROPPED;
        case SubscriptionDecision.RejectWithError reject -> {
          sendError(
              user,
              stompSessionId,
              reject.code(),
              reject.message(),
              Map.of("retryAfterMs", reject.retryAfterMs()));
          yield DROPPED;
        }
      };
    }
    return null;
  }

  private void sendError(
      AuthenticatedUser user,
      String stompSessionId,
      String code,
      String message,
      Map<String, Object> extra) {
    SimpMessageHeaderAccessor headerAccessor = SimpMessageHeaderAccessor.create();
    headerAccessor.setSessionId(stompSessionId);
    headerAccessor.setLeaveMutable(true);
    Map<String, Object> payload = new HashMap<>(Map.of("code", code, "message", message));
    payload.putAll(extra);
    messagingTemplate.convertAndSendToUser(
        user.getName(),
        "/queue/errors",
        Map.of("type", "ERROR", "payload", payload),
        headerAccessor.getMessageHeaders());
  }

  private static AuthenticatedUser principalOf(StompHeaderAccessor accessor) {
    if (accessor.getUser() instanceof AuthenticatedUser user) {
      return user;
    }
    throw new IllegalStateException(
        "a non-CONNECT frame reached the interceptor with no principal set — CONNECT must have"
            + " been rejected");
  }

  private static String requireSessionId(StompHeaderAccessor accessor) {
    String sessionId = accessor.getSessionId();
    if (sessionId == null) {
      throw new IllegalStateException("a STOMP frame carries no session id");
    }
    return sessionId;
  }

  /** A non-null sentinel meaning "drop this message" — the caller returns {@code null} itself. */
  private static final Message<?> DROPPED = MessageBuilder.withPayload(new byte[0]).build();
}
