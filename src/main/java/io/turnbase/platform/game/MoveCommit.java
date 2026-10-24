package io.turnbase.platform.game;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * The Redis writes that follow an {@code OK} {@code ProcessResult}, in one round trip: the
 * consecutive-failure counter reset (FR-FAIL-003), the retained {@code log} entries (FR-LOG-004)
 * and the {@code delivered:} watermark ({@code game-host-internals.md} §9.2). One Lua script, so
 * the three land together or not at all; the reads that gate dispatch stay where they are.
 *
 * <p>The watermark is part of the script, so the script runs <em>after</em> fan-out and the
 * watermark is never advanced before the effects have been sent. That is also why the log entries
 * are stored here and not as each {@code log} effect is published.
 */
@Component
class MoveCommit {

  private static final RedisScript<Long> SCRIPT =
      new DefaultRedisScript<>(
          """
          redis.call('SET', KEYS[1], '0', 'PX', ARGV[1])
          if #ARGV > 2 then
            redis.call('RPUSH', KEYS[3], unpack(ARGV, 3))
            redis.call('PEXPIRE', KEYS[3], ARGV[1])
          end
          if ARGV[2] ~= '' then
            redis.call('SET', KEYS[2], ARGV[2], 'PX', ARGV[1])
          end
          return 1
          """,
          Long.class);

  private final StringRedisTemplate redis;
  private final GameLogStore logStore;

  MoveCommit(StringRedisTemplate redis, GameLogStore logStore) {
    this.redis = redis;
    this.logStore = logStore;
  }

  /**
   * @param deliveredSequence the watermark to set, or {@code null} when the result was a replay or
   *     its fan-out failed, so the watermark stays where it is
   * @param log entries to append to {@code gamelog:}, empty when there are none
   */
  void record(UUID sessionId, @Nullable Long deliveredSequence, List<GameLogStore.Entry> log) {
    List<String> args = new ArrayList<>();
    args.add(Long.toString(HostRegistry.KEY_TTL.toMillis()));
    args.add(deliveredSequence == null ? "" : Long.toString(deliveredSequence));
    log.forEach(entry -> args.add(logStore.json(entry)));
    redis.execute(
        SCRIPT,
        List.of("failcount:" + sessionId, "delivered:" + sessionId, GameLogStore.key(sessionId)),
        args.toArray());
  }
}
