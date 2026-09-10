package io.turnbase.platform.room;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * {@code room-lifecycle.md} §1 (all six subsections) — the atomic claim script that is also the
 * failure detector (§1.2), compare-and-delete release (§1.3), the room-row half of adoption (§1.4
 * steps 1–5 and 7, the mass grace-reservation open added by {@code IMP-M2-07}, {@code IMP-GATE-48};
 * step 6 needs Game Host session-dispatch infrastructure that does not exist until M3 — recorded
 * here, not gated, since this item's own Done-when never required it; step 8 needs no code, since
 * this method never publishes a per-player event), the owner-epoch fence primitive other
 * room-mutating items build on (§1.5 layer 4), and the liveness lease with self-eviction on lapse
 * (§1.5 layers 2–3).
 *
 * <p>Package-private: every {@code IMP-M2-*} consumer of the claim (REST in {@code IMP-M2-01},
 * STOMP subscribe-time re-verification in {@code IMP-M2-02}) lives in this same {@code room}
 * feature package, so nothing outside it needs to see this type.
 */
@Component
class RoomOwnershipService {

  private static final Logger log = LoggerFactory.getLogger(RoomOwnershipService.class);

  private static final String REDIRECT = "0";
  private static final String ALREADY_MINE = "1";
  private static final String ADOPTED = "2";

  /**
   * {@code room-lifecycle.md} §1.7: "the handler retries internally for a few hundred milliseconds
   * and then gives up." Three attempts, 100ms apart — shared by every caller of {@link
   * #claimWithRetry} (REST's {@code RoomService}, STOMP's subscribe-time re-verification), so the
   * ~200ms budget is spent identically regardless of which protocol is asking.
   */
  private static final int RETRY_ATTEMPTS = 3;

  private static final Duration RETRY_DELAY = Duration.ofMillis(100);

  /** {@code room-lifecycle.md} §1.2, verbatim. Returns {@code [0|1|2, ownerPodId, epoch]}. */
  private static final RedisScript<List> CLAIM_SCRIPT =
      new DefaultRedisScript<>(
          """
          local cur = redis.call('GET', KEYS[1])
          if cur == ARGV[1] then
            return {1, cur, redis.call('GET', KEYS[2])}
          end
          if cur ~= false and redis.call('EXISTS', 'pod:' .. cur .. ':alive') == 1 then
            return {0, cur, 0}
          end
          redis.call('SET', KEYS[1], ARGV[1])
          local ep = redis.call('INCR', KEYS[2])
          return {2, ARGV[1], ep}
          """,
          List.class);

  /** {@code room-lifecycle.md} §1.3, verbatim: compare-and-delete. */
  private static final RedisScript<Long> RELEASE_SCRIPT =
      new DefaultRedisScript<>(
          """
          if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) end
          return 0
          """,
          Long.class);

  private final StringRedisTemplate redis;
  private final JdbcClient jdbcClient;
  private final PodIdentity pod;
  private final RoomProperties properties;
  private final RoomOwnershipMetrics metrics;
  private final ApplicationEventPublisher events;

  /** Rooms this instance currently believes it owns: roomId -> (epoch, in-JVM version counter). */
  private final Map<UUID, OwnedRoom> owned = new ConcurrentHashMap<>();

  private volatile boolean evicted = false;

  RoomOwnershipService(
      StringRedisTemplate redis,
      JdbcClient jdbcClient,
      PodIdentity pod,
      RoomProperties properties,
      RoomOwnershipMetrics metrics,
      ApplicationEventPublisher events) {
    this.redis = redis;
    this.jdbcClient = jdbcClient;
    this.pod = pod;
    this.properties = properties;
    this.metrics = metrics;
    this.events = events;
  }

  /** The liveness key's first write. {@code XX} would fail on a key that has never existed. */
  void startLiveness() {
    metrics.record(
        () -> {
          redis
              .opsForValue()
              .set(livenessKey(), Instant.now().toString(), properties.lease().ttl());
          return null;
        });
  }

  /**
   * {@code room-lifecycle.md} §1.1/§1.5 layers 2–3, run on {@code lease.renewInterval}: renew this
   * instance's liveness lease with {@code SET ... XX}, and independently re-verify {@code
   * room:{roomId}:pod} for every locally-owned room. A {@code SET ... XX} failure is a proven lease
   * lapse (never a mere renewal-failure guess — see {@link #renewLease()}) and is unconditionally
   * fatal for the whole instance; a single room's key naming someone else is a narrower, per-room
   * loss that does not affect the others.
   */
  void renewAndRecheck() {
    if (evicted) {
      return;
    }
    if (!renewLease()) {
      selfEvictInstance();
      return;
    }
    for (UUID roomId : owned.keySet()) {
      String ownerNow = metrics.record(() -> redis.opsForValue().get(roomKey(roomId)));
      if (!pod.id().equals(ownerNow)) {
        evictRoom(roomId, "ownership re-check found a different owner");
      }
    }
  }

  private boolean renewLease() {
    Boolean renewed =
        metrics.record(
            () ->
                redis
                    .opsForValue()
                    .setIfPresent(
                        livenessKey(), Instant.now().toString(), properties.lease().ttl()));
    return Boolean.TRUE.equals(renewed);
  }

  /**
   * §1.5 layer 3: unconditionally fatal. Drops every owned room locally and stops renewing — {@code
   * livenessKey()} is never recreated, so this instance can never again pass another's claim
   * script's liveness check.
   */
  private void selfEvictInstance() {
    evicted = true;
    log.warn("pod {} self-evicting: liveness lease lapsed", pod.id());
    for (UUID roomId : List.copyOf(owned.keySet())) {
      evictRoom(roomId, "instance self-eviction");
    }
  }

  private void evictRoom(UUID roomId, String reason) {
    if (owned.remove(roomId) != null) {
      events.publishEvent(new RoomOwnershipLostEvent(roomId, reason));
    }
  }

  /**
   * {@code room-lifecycle.md} §1.2. A client entering or re-entering a room, or the reconciliation
   * sweep, calling this on a room whose owner's lease has lapsed always resolves to adoption or
   * "already mine" — never a permanent redirect, since result {@code 1} is redirect-and-retry.
   */
  ClaimOutcome claim(UUID roomId) {
    List<?> reply =
        metrics.record(
            () ->
                redis.execute(CLAIM_SCRIPT, List.of(roomKey(roomId), epochKey(roomId)), pod.id()));
    String result = String.valueOf(reply.get(0));
    if (ALREADY_MINE.equals(result)) {
      return new ClaimOutcome.AlreadyMine(toLong(reply.get(2)));
    }
    if (REDIRECT.equals(result)) {
      return new ClaimOutcome.Redirect((String) reply.get(1));
    }
    if (!ADOPTED.equals(result)) {
      throw new IllegalStateException("claim script returned unexpected result " + result);
    }
    return adopt(roomId, toLong(reply.get(2)));
  }

  /**
   * {@link #claim} with §1.7's bounded retry: a {@code Redirect} or a lost adoption race ({@code
   * Superseded} — this class's own "never retry in place" applies to the room-row write, not to
   * re-running the whole claim script) is retried {@link #RETRY_ATTEMPTS} times before giving up.
   * The final outcome is returned as-is — the caller decides what "gave up" means for its own
   * protocol (a REST {@code 503} versus a STOMP {@code ROOM_UNAVAILABLE_RETRY} frame).
   */
  ClaimOutcome claimWithRetry(UUID roomId) {
    ClaimOutcome outcome = claim(roomId);
    for (int attempt = 2;
        attempt <= RETRY_ATTEMPTS
            && (outcome instanceof ClaimOutcome.Redirect
                || outcome instanceof ClaimOutcome.Superseded);
        attempt++) {
      sleep(RETRY_DELAY);
      outcome = claim(roomId);
    }
    return outcome;
  }

  private static void sleep(Duration duration) {
    try {
      Thread.sleep(duration);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted while retrying a room ownership claim", e);
    }
  }

  /** {@code StringRedisTemplate} deserializes every script reply element as a {@link String}. */
  private static long toLong(@Nullable Object value) {
    return Long.parseLong(String.valueOf(value));
  }

  /**
   * {@code room-lifecycle.md} §1.4 steps 1–3, 4–5 and 7: the room-row half of adoption, plus the
   * mass grace-reservation open ({@code IMP-GATE-48}). Steps 6 and 8 (session re-dispatch,
   * suppressed disconnect events) need Game Host dispatch infrastructure that does not exist yet
   * (Game Host session dispatch, M3) and emit no events respectively — step 8 needs no code, since
   * this method never publishes a per-player event.
   */
  private ClaimOutcome adopt(UUID roomId, long epoch) {
    Optional<Long> version =
        jdbcClient
            .sql(
                """
                UPDATE room
                   SET owner_instance_id = :podId,
                       owner_epoch = :epoch,
                       ownerless_since = NULL,
                       migration_window_expires_at = now() + make_interval(secs => :windowSeconds)
                 WHERE room_id = :roomId AND owner_epoch < :epoch
                RETURNING version
                """)
            .param("podId", pod.id())
            .param("epoch", epoch)
            .param("windowSeconds", (int) properties.migrationWindow().toSeconds())
            .param("roomId", roomId)
            .query(Long.class)
            .optional();
    if (version.isEmpty()) {
      // §1.4 step 3's guard: a later tenure already recorded itself under this room. Abandon —
      // never retry in place — and self-evict this room so no later write here is attempted.
      log.warn(
          "pod {} lost the adoption race for room {} at epoch {}: a later tenure already owns it",
          pod.id(),
          roomId,
          epoch);
      return new ClaimOutcome.Superseded();
    }
    // §1.4 steps 4-5 only apply to a genuine takeover from a previous, now-dead tenure — never to
    // a room's very first claim ever. The claim script's own "free, or lease lapsed" comment (§1.2)
    // conflates both into the same ADOPTED result, but the Redis epoch counter (INCR'd from 0)
    // still distinguishes them: epoch 1 is always a fresh room's first claim, when every
    // participant genuinely has connection_count = 0 because nobody has subscribed yet — not
    // because anyone was orphaned. Running the mass-open there would hand a brand-new joiner a
    // grace reservation before their own first SUBSCRIBE ever completes.
    if (epoch > 1) {
      openMassGraceReservations(roomId);
    }
    owned.put(roomId, new OwnedRoom(epoch, new AtomicLong(version.get())));
    return new ClaimOutcome.Adopted(epoch, version.get());
  }

  /**
   * {@code room-lifecycle.md} §1.4 steps 4–5 ({@code IMP-GATE-48}): every participant left with
   * {@code connection_count > 0} lost their connection along with the previous owner (FR-DC-006).
   * Step 5 zeroes {@code connection_count} for every role — a spectator's socket died too, and
   * {@link RoomStateSnapshot.PlayerEntry#connected} / {@link RoomStateSnapshot.SpectatorEntry} both
   * read this column. Step 4 additionally opens a grace reservation, but only for {@code PLAYER}
   * role, matching §3.1's own scope (a spectator is dropped immediately, FR-DC-003, never given a
   * reservation) — this is §3.1's statement applied to every eligible player in one pass rather
   * than one row, {@code COALESCE}-guarded so a reservation already running from an earlier
   * individual disconnect is never reset. No event is published (§1.4 step 8) — every client of
   * this room is already disconnected by I1.
   */
  private void openMassGraceReservations(UUID roomId) {
    jdbcClient
        .sql(
            "UPDATE room_participant SET connection_count = 0"
                + " WHERE room_id = :roomId AND connection_count > 0 AND left_at IS NULL")
        .param("roomId", roomId)
        .update();
    jdbcClient
        .sql(
            """
            UPDATE room_participant
               SET grace_reservation_id = COALESCE(grace_reservation_id, gen_random_uuid()),
                   grace_expires_at =
                     COALESCE(grace_expires_at, now() + make_interval(secs => :graceSeconds))
             WHERE room_id = :roomId AND role = 'PLAYER' AND left_at IS NULL
               AND connection_count = 0
            """)
        .param("roomId", roomId)
        .param("graceSeconds", (int) properties.gracePeriod().toSeconds())
        .update();
  }

  /**
   * {@code room-lifecycle.md} §1.3: graceful release only. A crash leaves the key for the liveness
   * lease to expire instead — this method is never called from {@link #selfEvictInstance()}.
   */
  void release(UUID roomId) {
    OwnedRoom state = owned.get(roomId);
    if (state == null) {
      return;
    }
    Long deleted =
        metrics.record(() -> redis.execute(RELEASE_SCRIPT, List.of(roomKey(roomId)), pod.id()));
    if (deleted != null && deleted > 0) {
      jdbcClient
          .sql(
              "UPDATE room SET owner_instance_id = NULL, ownerless_since = now()"
                  + " WHERE room_id = :roomId AND owner_epoch = :epoch")
          .param("roomId", roomId)
          .param("epoch", state.epoch())
          .update();
    }
    owned.remove(roomId);
  }

  /** The epoch every fenced mutation of this room must include, per §1.5 layer 4. */
  Optional<Long> epochOf(UUID roomId) {
    OwnedRoom state = owned.get(roomId);
    return state == null ? Optional.empty() : Optional.of(state.epoch());
  }

  /** {@code room-lifecycle.md} §2.1 step 3: assigned, never {@code max()}-ed. */
  long nextVersion(UUID roomId) {
    OwnedRoom state = owned.get(roomId);
    if (state == null) {
      throw new IllegalStateException("room " + roomId + " is not owned by this instance");
    }
    return state.version().incrementAndGet();
  }

  /**
   * {@code room-lifecycle.md} §2.1: "a failed CAS leaves the in-memory room state dirty... this is
   * why self-eviction drops in-memory state rather than attempting to repair it." Called by {@link
   * RoomEventExecutor} when its fenced version commit affects zero rows — the room's local view,
   * including {@link #nextVersion}'s counter, is no longer trustworthy regardless of whether the
   * cause was a genuine ownership change or this instance's own prior failed attempt; either way
   * the fix is the same: drop it and let the next claim re-initialize from the database.
   */
  void evictAfterFailedCas(UUID roomId) {
    evictRoom(roomId, "fenced version commit failed");
  }

  private String livenessKey() {
    return "pod:" + pod.id() + ":alive";
  }

  private static String roomKey(UUID roomId) {
    return "room:" + roomId + ":pod";
  }

  private static String epochKey(UUID roomId) {
    return "room:" + roomId + ":epoch";
  }

  private record OwnedRoom(long epoch, AtomicLong version) {}

  sealed interface ClaimOutcome {
    record AlreadyMine(long epoch) implements ClaimOutcome {}

    record Adopted(long epoch, long version) implements ClaimOutcome {}

    record Redirect(@Nullable String ownerPodId) implements ClaimOutcome {}

    /** §1.4 step 3's guard failed: a later tenure already owns this room. Never retry in place. */
    record Superseded() implements ClaimOutcome {}
  }
}
