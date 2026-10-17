package io.turnbase.platform.game;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * The Game Host registry in Redis — {@code game-host-internals.md} §13's {@code hosts}, {@code
 * host:}, {@code hostlease:} and {@code contexts:} keys and §11's host-selection rule. Every
 * replica of the monolith reads and writes these, which is what lets the Game Host dial one stable
 * address while the dispatch path addresses individual pods.
 *
 * <p>The Game Host never touches Redis's registry keys; it reaches them only through {@code
 * GameHostRegistryService}, whose implementation delegates here.
 */
@Component
class HostRegistry {

  /** §13: every registry key except the lease and the {@code hosts} set carries a 24h backstop. */
  static final Duration KEY_TTL = Duration.ofHours(24);

  private static final String HOSTS = "hosts";

  /** {@code contexts:{id}} is replaced by each full snapshot, atomically, never patched. */
  private static final DefaultRedisScript<Long> REPLACE_CONTEXTS =
      new DefaultRedisScript<>(
          """
          redis.call('DEL', KEYS[1])
          for i = 1, #ARGV - 1 do redis.call('SADD', KEYS[1], ARGV[i]) end
          if #ARGV > 1 then redis.call('EXPIRE', KEYS[1], ARGV[#ARGV]) end
          return 1
          """,
          Long.class);

  private final StringRedisTemplate redis;
  private final OrchestrationProperties properties;

  HostRegistry(StringRedisTemplate redis, OrchestrationProperties properties) {
    this.redis = redis;
    this.properties = properties;
  }

  /**
   * §11: sets the {@code hosts} member, the {@code host:} hash and the lease, in that order, so a
   * concurrent selection never sees a lease with no entry behind it. Idempotent for an {@code
   * instanceId} already present — a re-registering host at a new address simply overwrites.
   */
  void register(String instanceId, String address, Collection<String> runtimeTypes) {
    redis.opsForSet().add(HOSTS, instanceId);
    redis
        .opsForHash()
        .putAll(
            hostKey(instanceId),
            Map.of(
                "address", address,
                "runtimeTypes", String.join(",", runtimeTypes),
                "activeSessions", "0",
                "activeContexts", "0",
                "memoryUsedPercent", "0",
                "draining", "false"));
    redis.expire(hostKey(instanceId), KEY_TTL);
    renewLease(instanceId);
  }

  /**
   * Refreshes the load signals and the lease. Returns {@code false} for an instance that is not
   * registered (its entry expired or it deregistered): the caller tells the host to re-register
   * rather than resurrecting a half entry with no address.
   */
  boolean heartbeat(String instanceId, int activeSessions, int activeContexts, int memoryPercent) {
    if (!Boolean.TRUE.equals(redis.hasKey(hostKey(instanceId)))) {
      return false;
    }
    redis
        .opsForHash()
        .putAll(
            hostKey(instanceId),
            Map.of(
                "activeSessions", Integer.toString(activeSessions),
                "activeContexts", Integer.toString(activeContexts),
                "memoryUsedPercent", Integer.toString(memoryPercent)));
    redis.expire(hostKey(instanceId), KEY_TTL);
    renewLease(instanceId);
    return true;
  }

  boolean isDraining(String instanceId) {
    return "true".equals(redis.opsForHash().get(hostKey(instanceId), "draining"));
  }

  /** Stops routing new sessions to the instance; the next heartbeat answers {@code drain=true}. */
  void drain(String instanceId) {
    if (Boolean.TRUE.equals(redis.hasKey(hostKey(instanceId)))) {
      redis.opsForHash().put(hostKey(instanceId), "draining", "true");
    }
  }

  /** {@code gameId:gameVersion} members, replaced atomically with each full snapshot. */
  void reportContexts(String instanceId, Collection<String> contextKeys) {
    List<String> args = new ArrayList<>(contextKeys);
    args.add(Long.toString(KEY_TTL.toSeconds()));
    redis.execute(REPLACE_CONTEXTS, List.of(contextsKey(instanceId)), args.toArray());
  }

  /**
   * §11: lease first, so a concurrent selection stops seeing the instance before its entry is half
   * gone.
   */
  void deregister(String instanceId) {
    redis.delete(leaseKey(instanceId));
    redis.delete(hostKey(instanceId));
    redis.delete(contextsKey(instanceId));
    redis.opsForSet().remove(HOSTS, instanceId);
  }

  /**
   * Takes an instance out of selection for {@code quarantine-ttl}: a host that held a {@code
   * CreateSession} past its deadline is alive (lease, heartbeat, health) but cannot boot a guest —
   * the lease alone cannot tell. The key expires on its own, so a host that was only slow is routed
   * to again.
   */
  void quarantine(String instanceId) {
    redis.opsForValue().set(quarantineKey(instanceId), "1", properties.quarantineTtl());
  }

  boolean isQuarantined(String instanceId) {
    return Boolean.TRUE.equals(redis.hasKey(quarantineKey(instanceId)));
  }

  /** The dial address, always re-read — never copied into {@code routing:}. */
  Optional<String> addressOf(String instanceId) {
    return Optional.ofNullable((String) redis.opsForHash().get(hostKey(instanceId), "address"));
  }

  boolean isLive(String instanceId) {
    return Boolean.TRUE.equals(redis.hasKey(leaseKey(instanceId)));
  }

  /**
   * §11's deterministic rule: live lease, not draining, not quarantined, lists {@code jvm}; prefer
   * a warm host, then lowest {@code activeSessions}, then lexical {@code instanceId}. A member
   * whose {@code host:} entry has expired is dropped from {@code hosts} as it is met.
   */
  Optional<String> selectHost(String gameId, String gameVersion) {
    return selectHost(gameId, gameVersion, Set.of());
  }

  /** As {@link #selectHost(String, String)}, never choosing {@code excluded} (a failed host). */
  Optional<String> selectHost(String gameId, String gameVersion, String excluded) {
    return selectHost(gameId, gameVersion, Set.of(excluded));
  }

  /** As above, for several failed hosts. */
  Optional<String> selectHost(String gameId, String gameVersion, Set<String> excluded) {
    Set<String> members = redis.opsForSet().members(HOSTS);
    if (members == null) {
      return Optional.empty();
    }
    String contextKey = gameId + ":" + gameVersion;
    List<Candidate> candidates = new ArrayList<>();
    for (String instanceId : members) {
      if (excluded.contains(instanceId)) {
        continue;
      }
      Map<Object, Object> entry = redis.opsForHash().entries(hostKey(instanceId));
      if (entry.isEmpty()) {
        redis.opsForSet().remove(HOSTS, instanceId);
        continue;
      }
      if (!isLive(instanceId)
          || "true".equals(entry.get("draining"))
          || isQuarantined(instanceId)) {
        continue;
      }
      if (!List.of(String.valueOf(entry.get("runtimeTypes")).split(",")).contains("jvm")) {
        continue;
      }
      boolean warm =
          Boolean.TRUE.equals(redis.opsForSet().isMember(contextsKey(instanceId), contextKey));
      candidates.add(
          new Candidate(
              instanceId, warm, Integer.parseInt(String.valueOf(entry.get("activeSessions")))));
    }
    return candidates.stream()
        .min(
            Comparator.comparing((Candidate c) -> !c.warm())
                .thenComparingInt(Candidate::activeSessions)
                .thenComparing(Candidate::instanceId))
        .map(Candidate::instanceId);
  }

  private void renewLease(String instanceId) {
    redis.opsForValue().set(leaseKey(instanceId), instanceId, properties.leaseTtl());
  }

  private static String hostKey(String instanceId) {
    return "host:" + instanceId;
  }

  private static String leaseKey(String instanceId) {
    return "hostlease:" + instanceId;
  }

  private static String quarantineKey(String instanceId) {
    return "hostquarantine:" + instanceId;
  }

  private static String contextsKey(String instanceId) {
    return "contexts:" + instanceId;
  }

  private record Candidate(String instanceId, boolean warm, int activeSessions) {}
}
