package io.turnbase.platform.websocket;

import io.turnbase.platform.common.SessionCookies;
import java.util.List;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.converter.JacksonJsonMessageConverter;
import org.springframework.messaging.converter.MessageConverter;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
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

  /**
   * Waiting frames per STOMP channel executor: none. A thread pool only grows past its core size
   * when its queue refuses a task, and the inbound handlers block on the Game Host, so a queue in
   * front of four core threads capped the platform at four concurrent calls (32 pairs: move round
   * trip p50 25 ms -> 43 ms, platform CPU 2.3 -> 1.9 cores). With a hand-off the pool grows to its
   * maximum and a frame beyond that runs on the publishing thread.
   */
  static final int CHANNEL_QUEUE_CAPACITY = 0;

  /** Idle seconds after which a STOMP channel thread is released, core threads included. */
  static final int CHANNEL_KEEP_ALIVE_SECONDS = 60;

  private final WebSocketChannelInterceptor channelInterceptor;
  private final WebSocketTraceInterceptor traceInterceptor;
  private final SubscribeReceiptInterceptor receiptInterceptor;
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
      SubscribeReceiptInterceptor receiptInterceptor,
      JsonMapper jsonMapper,
      SessionCookies sessionCookies) {
    this.sessionCookies = sessionCookies;
    this.jsonMapper = jsonMapper;
    this.traceInterceptor = traceInterceptor;
    this.receiptInterceptor = receiptInterceptor;
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
    registration.interceptors(channelInterceptor, traceInterceptor, receiptInterceptor);
    registration.executor(channelExecutor("clientInboundChannel-"));
  }

  @Override
  public void configureClientOutboundChannel(ChannelRegistration registration) {
    registration.executor(channelExecutor("clientOutboundChannel-"));
  }

  /**
   * {@code IMP-M16-10}: the STOMP channel executors are bounded and release their threads. Core =
   * available processors, maximum = four times that, a hand-off instead of a queue, whose overflow
   * runs on the publishing thread (back-pressure instead of an unbounded backlog), and every
   * thread, core included, times out after {@link #CHANNEL_KEEP_ALIVE_SECONDS} idle seconds. The
   * broker channel has no executor on purpose: with the simple broker it delivers on the
   * publisher's thread (the load-test census shows no {@code brokerChannel-} thread), and giving it
   * a pool would add a hand-off to every fan-out.
   *
   * <p>Set with {@code executor(...)}, which wins over {@code taskExecutor(...)}: Spring Boot's own
   * configurer (order 0, this one runs after it) hands both channels the context's only {@code
   * AsyncTaskExecutor}, and without this override they would share that pool and its settings.
   */
  static ThreadPoolTaskExecutor channelExecutor(String threadNamePrefix) {
    int processors = Runtime.getRuntime().availableProcessors();
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setThreadNamePrefix(threadNamePrefix);
    executor.setCorePoolSize(processors);
    executor.setMaxPoolSize(4 * processors);
    executor.setQueueCapacity(CHANNEL_QUEUE_CAPACITY);
    executor.setKeepAliveSeconds(CHANNEL_KEEP_ALIVE_SECONDS);
    executor.setAllowCoreThreadTimeOut(true);
    executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
    return executor;
  }

  /**
   * Not an autowire candidate: the {@code @Scheduled} jobs resolve the {@code taskScheduler} that
   * {@code SchedulingPoolConfig} defines (the broker's own scheduler is the only other candidate,
   * and the name settles it), and a third makes the resolution ambiguous. The broker receives this
   * one by direct call above.
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
