package io.turnbase.platform.websocket;

import io.turnbase.platform.common.SessionCookies;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.converter.JacksonJsonMessageConverter;
import org.springframework.messaging.converter.MessageConverter;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import tools.jackson.databind.json.JsonMapper;

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
  private final JsonMapper jsonMapper;
  private final SessionCookies sessionCookies;

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
      WebSocketTraceInterceptor traceInterceptor,
      JsonMapper jsonMapper,
      SessionCookies sessionCookies) {
    this.sessionCookies = sessionCookies;
    this.jsonMapper = jsonMapper;
    this.traceInterceptor = traceInterceptor;
    this.channelInterceptor = channelInterceptor;
  }

  @Override
  public void registerStompEndpoints(StompEndpointRegistry registry) {
    registry
        .addEndpoint("/ws")
        .addInterceptors(new SessionCookieHandshakeInterceptor(sessionCookies));
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

  /**
   * STOMP frames are written by the platform's mapper, not by a converter Spring builds for itself:
   * the wire format of a frame is then pinned in {@code JacksonConfig} like every other body.
   */
  @Override
  public boolean configureMessageConverters(List<MessageConverter> converters) {
    converters.add(new JacksonJsonMessageConverter(jsonMapper));
    return false;
  }

  @Override
  public void configureClientInboundChannel(ChannelRegistration registration) {
    registration.interceptors(channelInterceptor, traceInterceptor);
  }

  /**
   * Not an autowire candidate: Spring Boot's own {@code taskScheduler} is the one
   * {@code @Scheduled} jobs resolve by type, and a second candidate makes the scheduling
   * post-processor log "more than one TaskScheduler bean" at start-up. The broker receives this one
   * by direct call above.
   */
  @Bean(autowireCandidate = false)
  TaskScheduler heartbeatTaskScheduler() {
    ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(1);
    scheduler.setThreadNamePrefix("ws-heartbeat-");
    scheduler.initialize();
    return scheduler;
  }
}
