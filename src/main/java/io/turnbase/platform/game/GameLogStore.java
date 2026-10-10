package io.turnbase.platform.game;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * The one thing the platform retains per session (FR-DELIVER-003, FR-LOG-004): {@code log} effect
 * entries, in {@code (sequence, index)} order, so a late joiner or reconnecting client can be given
 * the whole log in {@code GAME_SYNC}. {@code gamelog:{sessionId}} is a Redis list; it shares the
 * session key family's lifecycle and is deleted with it.
 */
@Component
public class GameLogStore {

  private static final Logger log = LoggerFactory.getLogger(GameLogStore.class);

  /** One retained entry, exactly the shape of {@code GAME_SYNC.payload.log}. */
  public record Entry(long sequence, int index, String entry) {}

  private final StringRedisTemplate redis;
  private final JsonMapper objectMapper;

  GameLogStore(StringRedisTemplate redis, JsonMapper objectMapper) {
    this.redis = redis;
    this.objectMapper = objectMapper;
  }

  void append(UUID sessionId, Entry entry) {
    String key = key(sessionId);
    try {
      redis.opsForList().rightPush(key, objectMapper.writeValueAsString(entry));
      redis.expire(key, HostRegistry.KEY_TTL);
    } catch (JacksonException e) {
      throw new IllegalStateException("log entry is not serialisable", e);
    }
  }

  /** The full log from session start; empty when the session has none. */
  public List<Entry> read(UUID sessionId) {
    List<String> raw = redis.opsForList().range(key(sessionId), 0, -1);
    List<Entry> entries = new ArrayList<>();
    if (raw == null) {
      return entries;
    }
    for (String json : raw) {
      try {
        entries.add(objectMapper.readValue(json, Entry.class));
      } catch (JacksonException e) {
        log.warn("sessionId={} unreadable game-log entry skipped", sessionId);
      }
    }
    return entries;
  }

  static String key(UUID sessionId) {
    return "gamelog:" + sessionId;
  }
}
