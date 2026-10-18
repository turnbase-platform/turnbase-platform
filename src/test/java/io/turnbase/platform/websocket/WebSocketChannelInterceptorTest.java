package io.turnbase.platform.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.turnbase.platform.common.AuthenticatedUser;
import io.turnbase.platform.session.SessionService;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;

/** The {@code CONNECT} path of the interceptor: how the per-user connection cap is applied. */
class WebSocketChannelInterceptorTest {

  private static final int CONFIGURED_CAP = 4;

  private final SessionService sessionService = mock(SessionService.class);
  private final WebSocketConnectionRegistry registry = mock(WebSocketConnectionRegistry.class);
  private final WebSocketRateLimiter rateLimiter = mock(WebSocketRateLimiter.class);
  private final AuthenticatedUser user =
      new AuthenticatedUser(UUID.randomUUID(), UUID.randomUUID());

  private final WebSocketChannelInterceptor interceptor =
      new WebSocketChannelInterceptor(
          sessionService,
          registry,
          rateLimiter,
          properties(),
          mock(WebSocketFrameSizeGuard.class),
          List.of(),
          mock(SimpMessagingTemplate.class));

  {
    when(sessionService.resolve("token")).thenReturn(new SessionService.Resolution.Valid(user));
    when(registry.tryRegister(any(), any(), anyInt())).thenReturn(true);
  }

  @Test
  void disabledConnectionCapAdmitsMoreConnectionsThanTheCap() {
    when(rateLimiter.connectionCapEnforced()).thenReturn(false);

    interceptor.preSend(connectFrame("s1"), mock(MessageChannel.class));

    verify(registry).tryRegister(eq("s1"), eq(user), eq(Integer.MAX_VALUE));
  }

  @Test
  void enabledConnectionCapPassesTheConfiguredCap() {
    when(rateLimiter.connectionCapEnforced()).thenReturn(true);

    Message<?> result = interceptor.preSend(connectFrame("s1"), mock(MessageChannel.class));

    assertThat(result).isNotNull();
    verify(registry).tryRegister(eq("s1"), eq(user), eq(CONFIGURED_CAP));
  }

  private static Message<?> connectFrame(String stompSessionId) {
    StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
    accessor.setSessionId(stompSessionId);
    accessor.setSessionAttributes(
        Map.of(SessionCookieHandshakeInterceptor.SESSION_TOKEN_ATTRIBUTE, "token"));
    accessor.setLeaveMutable(true);
    return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
  }

  private static WebSocketRateLimitProperties properties() {
    var bucket = new WebSocketRateLimitProperties.Bucket(10, 10, Duration.ofSeconds(1));
    return new WebSocketRateLimitProperties(
        bucket,
        bucket,
        bucket,
        bucket,
        bucket,
        bucket,
        CONFIGURED_CAP,
        20,
        Duration.ofSeconds(10),
        new WebSocketRateLimitProperties.Lockout(Duration.ofSeconds(30), Duration.ofMinutes(15)),
        WebSocketRateLimitProperties.Switches.allEnabled());
  }
}
