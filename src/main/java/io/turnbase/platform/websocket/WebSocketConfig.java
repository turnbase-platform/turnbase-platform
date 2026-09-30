package io.turnbase.platform.websocket;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * {@code websocket.md} § Connection: STOMP over WebSocket at {@code /ws}, no SockJS — modern
 * browsers and the React frontend need no fallback transport. Heartbeats 10000/10000ms each
 * direction require an explicit {@link TaskScheduler} bean or the server advertises heartbeats it
 * never sends (a documented Spring trap, not a guess). {@code setPreservePublishOrder(true)} keeps
 * publish order across destinations for one client session, needed once effect fan-out lands
 * (`IMP-M3-*`) but harmless to set now. The path matcher uses {@code .} as the destination
 * separator — every destination in this corpus (`/topic/room.{roomId}`,
 * `/app/game.{sessionId}.action`) is written against that convention.
 */
@Configuration
@EnableWebSocketMessageBroker
class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

  private static final long[] HEARTBEAT = {10_000, 10_000};

  private final WebSocketChannelInterceptor channelInterceptor;
  private final WebSocketTraceInterceptor traceInterceptor;

  /**
   * {@code @Lazy}: this configurer is itself a dependency of {@code
   * DelegatingWebSocketMessageBrokerConfiguration}, which must finish collecting every {@code
   * WebSocketMessageBrokerConfigurer} bean before the broker infrastructure (including {@code
   * clientOutboundChannel}/{@code SimpMessagingTemplate}) exists — and {@link
   * WebSocketChannelInterceptor} depends on exactly that infrastructure. Eagerly resolving it here
   * would be circular; a lazy proxy defers resolution until the channel actually dispatches a
   * frame, by which point the broker is fully initialized.
   */
  WebSocketConfig(
      @Lazy WebSocketChannelInterceptor channelInterceptor,
      WebSocketTraceInterceptor traceInterceptor) {
    this.traceInterceptor = traceInterceptor;
    this.channelInterceptor = channelInterceptor;
  }

  @Override
  public void registerStompEndpoints(StompEndpointRegistry registry) {
    registry.addEndpoint("/ws").addInterceptors(new SessionCookieHandshakeInterceptor());
  }

  @Override
  public void configureMessageBroker(MessageBrokerRegistry registry) {
    registry.setPathMatcher(new AntPathMatcher("."));
    registry
        .enableSimpleBroker("/topic", "/queue")
        .setHeartbeatValue(HEARTBEAT)
        .setTaskScheduler(heartbeatTaskScheduler());
    registry.setApplicationDestinationPrefixes("/app");
    registry.setPreservePublishOrder(true);
  }

  @Override
  public void configureClientInboundChannel(ChannelRegistration registration) {
    registration.interceptors(channelInterceptor, traceInterceptor);
  }

  @Bean
  TaskScheduler heartbeatTaskScheduler() {
    ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(1);
    scheduler.setThreadNamePrefix("ws-heartbeat-");
    scheduler.initialize();
    return scheduler;
  }
}
