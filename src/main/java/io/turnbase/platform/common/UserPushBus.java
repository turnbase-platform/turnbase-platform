package io.turnbase.platform.common;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code IMP-M18-08} / ADR-0020 §4: the one Redis pub/sub seam for <em>user-scoped</em> pushes (SSE
 * alerts, a persisted notification's live push, session revocation). Room traffic never goes
 * through here (ADR-0002).
 *
 * <p>The caller delivers on its own instance first and then {@link #publish}es; every other
 * instance's {@link #subscribe}d handler runs for the message, and the publisher's own copy is
 * skipped. Local delivery therefore never depends on Redis, and nothing is delivered twice.
 * Delivery stays best-effort: a publish that fails is logged and dropped, exactly as an alert to a
 * user with no stream always was ({@code sse-notifications.md}).
 *
 * <p>Channels are {@code turnbase:push:{name}}; the message is {@code {"origin": instanceId,
 * "payload": {...}}}.
 */
@Component
public class UserPushBus {

  private static final Logger log = LoggerFactory.getLogger(UserPushBus.class);
  private static final String CHANNEL_PREFIX = "turnbase:push:";
  private static final TypeReference<Map<String, Object>> MESSAGE_TYPE = new TypeReference<>() {};

  private final String instanceId = UUID.randomUUID().toString();
  private final StringRedisTemplate redis;
  private final RedisMessageListenerContainer container;
  private final JsonMapper objectMapper;
  private final Map<String, List<Consumer<Map<String, Object>>>> handlers =
      new ConcurrentHashMap<>();

  UserPushBus(
      StringRedisTemplate redis, RedisMessageListenerContainer container, JsonMapper objectMapper) {
    this.redis = redis;
    this.container = container;
    this.objectMapper = objectMapper;
  }

  /** Publishes {@code payload} on {@code channel} for the other instances; never throws. */
  public void publish(String channel, Map<String, Object> payload) {
    try {
      String body =
          objectMapper.writeValueAsString(Map.of("origin", instanceId, "payload", payload));
      redis.convertAndSend(CHANNEL_PREFIX + channel, body);
    } catch (RuntimeException e) {
      log.warn("User push dropped: channel={} error={}", channel, e.toString());
    }
  }

  /** Runs {@code handler} for each message another instance publishes on {@code channel}. */
  public void subscribe(String channel, Consumer<Map<String, Object>> handler) {
    List<Consumer<Map<String, Object>>> list =
        handlers.computeIfAbsent(channel, c -> new CopyOnWriteArrayList<>());
    boolean first = list.isEmpty();
    list.add(handler);
    if (first) {
      container.addMessageListener(
          (message, pattern) -> dispatch(channel, message),
          new ChannelTopic(CHANNEL_PREFIX + channel));
    }
  }

  @SuppressWarnings("unchecked")
  private void dispatch(String channel, Message message) {
    try {
      Map<String, Object> envelope =
          objectMapper.readValue(
              new String(message.getBody(), StandardCharsets.UTF_8), MESSAGE_TYPE);
      if (instanceId.equals(envelope.get("origin"))) {
        return;
      }
      Map<String, Object> payload = (Map<String, Object>) envelope.get("payload");
      for (Consumer<Map<String, Object>> handler : handlers.getOrDefault(channel, List.of())) {
        handler.accept(payload);
      }
    } catch (RuntimeException e) {
      log.warn("User push message ignored: channel={} error={}", channel, e.toString());
    }
  }
}
