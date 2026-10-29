package io.turnbase.platform.game;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.turnbase.grpc.gamehost.v1.ActionInput;
import io.turnbase.grpc.gamehost.v1.Effect;
import io.turnbase.grpc.gamehost.v1.GameFailureKind;
import io.turnbase.grpc.gamehost.v1.GameHostServiceGrpc;
import io.turnbase.grpc.gamehost.v1.GameOutcome;
import io.turnbase.grpc.gamehost.v1.ProcessInputRequest;
import io.turnbase.grpc.gamehost.v1.ProcessResult;
import io.turnbase.grpc.gamehost.v1.ProcessStatus;
import io.turnbase.platform.common.KeyedLocks;
import io.turnbase.platform.common.LifecycleLog;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * The Orchestration Service's input-dispatch half ({@code orchestration-gamehost-protocol.md}
 * *Processing semantics*, *Error model*): allocate a per-session sequence, dial the host {@code
 * routing:{sessionId}} names, and turn the answer into one {@link Outcome}. It never writes {@code
 * session:{id}} (IR-14). Effects reach {@link EffectDelivery} at most once per sequence — the
 * {@code delivered:} watermark is what makes a re-dispatched, replayed sequence harmless.
 */
@Service
public class GameInputService {

  private static final Logger log = LoggerFactory.getLogger(GameInputService.class);

  /** What the caller should do with the input it just handed over. */
  public enum Outcome {
    /** Applied; effects went to {@link EffectDelivery} (or were already delivered). */
    DELIVERED,
    /**
     * The game code failed this input. Not re-dispatched, sequence burned (GAME-I3); the caller
     * tells the acting player.
     */
    REJECTED,
    /** As {@link #REJECTED}, and it was the failure that reached the retry limit (FR-FAIL-003). */
    RETRY_LIMIT_REACHED,
    /** Infrastructure failure; re-dispatch the <em>same</em> sequence later. */
    RETRY,
    /** The host has no such session and no Redis state. */
    SESSION_NOT_FOUND,
    /** The session cannot be served at all (context could not load, message too large). */
    SESSION_FAILED
  }

  /**
   * An {@link Outcome} with, for the two game-failure outcomes, the {@link GameFailureKind} name
   * the acting player is told ({@code ACTION_REJECTED}); never the failure message.
   */
  public record Result(Outcome outcome, @Nullable String failureKind) {}

  private final HostRegistry registry;
  private final GameHostChannels channels;
  private final StringRedisTemplate redis;
  private final OrchestrationProperties properties;
  private final EffectDelivery effects;
  private final MoveCommit moveCommit;
  private final GameEndListener gameEnd;
  private final GameOutcomeRecorder outcomeRecorder;
  private final SessionRosterSource rosters;
  private final SessionFailureListener sessionFailure;
  private final int retryLimit;
  private final KeyedLocks<UUID> submitLocks = new KeyedLocks<>();

  GameInputService(
      HostRegistry registry,
      GameHostChannels channels,
      StringRedisTemplate redis,
      OrchestrationProperties properties,
      EffectDelivery effects,
      MoveCommit moveCommit,
      GameEndListener gameEnd,
      GameOutcomeRecorder outcomeRecorder,
      SessionRosterSource rosters,
      SessionFailureListener sessionFailure,
      @Value("${turnbase.gamehost.retry-limit:3}") int retryLimit) {
    this.registry = registry;
    this.channels = channels;
    this.redis = redis;
    this.properties = properties;
    this.effects = effects;
    this.moveCommit = moveCommit;
    this.gameEnd = gameEnd;
    this.outcomeRecorder = outcomeRecorder;
    this.rosters = rosters;
    this.sessionFailure = sessionFailure;
    this.retryLimit = retryLimit;
  }

  /** {@code seq:{sessionId}}, an atomic {@code INCR} — monotonic across every replica. */
  public long allocateSequence(UUID sessionId) {
    Long next = redis.opsForValue().increment("seq:" + sessionId);
    if (next == null) {
      throw new IllegalStateException("INCR returned null for session " + sessionId);
    }
    return next;
  }

  /**
   * The acting-player path ({@code 03-game-turn.md} §C): allocate a sequence and dispatch, serially
   * per session. The Game Host treats {@code sequence <= lastAppliedSequence} as a replay, so two
   * actions that allocated 43 and 44 and arrived as 44, 43 would silently drop the first — the
   * allocate-and-dispatch pair must not interleave. Room affinity puts every action of a session on
   * this pod, which is why a pod-local lock suffices here.
   */
  public Result submitAction(UUID sessionId, ActionInput action) {
    ReentrantLock lock = submitLocks.retain(sessionId);
    lock.lock();
    try {
      return processDetailed(
          ProcessInputRequest.newBuilder()
              .setSessionId(sessionId.toString())
              .setSequence(allocateSequence(sessionId))
              .setAction(action)
              .build());
    } finally {
      lock.unlock();
      submitLocks.release(sessionId);
    }
  }

  /** {@code request.sequence} must have been allocated by {@link #allocateSequence}. */
  public Outcome process(ProcessInputRequest request) {
    return processDetailed(request).outcome();
  }

  private Result processDetailed(ProcessInputRequest request) {
    UUID sessionId = UUID.fromString(request.getSessionId());
    String instanceId = redis.opsForValue().get("routing:" + sessionId);
    if (instanceId == null) {
      return new Result(Outcome.SESSION_NOT_FOUND, null);
    }
    Optional<String> address = registry.addressOf(instanceId);
    if (address.isEmpty()) {
      log.warn("Host {} of sessionId={} is gone; input left for retry", instanceId, sessionId);
      reassign(sessionId, instanceId);
      return infrastructure(failedAttempt(sessionId, Outcome.RETRY), sessionId);
    }
    ProcessResult result;
    try {
      result =
          GameHostServiceGrpc.newBlockingStub(channels.forAddress(address.get()))
              .withDeadlineAfter(properties.dispatchDeadline().toMillis(), TimeUnit.MILLISECONDS)
              .processInput(request)
              .getResult();
    } catch (StatusRuntimeException e) {
      return infrastructure(onTransportFailure(sessionId, instanceId, address.get(), e), sessionId);
    }
    if (result.getStatus() == ProcessStatus.PROCESS_STATUS_OK) {
      deliverOnce(sessionId, request.getSequence(), result);
      result.getEffectsList().stream()
          .filter(Effect::hasGameOver)
          .findFirst()
          .ifPresent(effect -> announceGameOver(sessionId, effect.getGameOver().getOutcome()));
      return new Result(Outcome.DELIVERED, null);
    }
    // Detail goes to the log only: it is game-supplied text and never reaches a client.
    log.warn(
        "Game failure sessionId={} sequence={} kind={} message={}",
        sessionId,
        request.getSequence(),
        result.getFailure().getKind(),
        result.getFailure().getMessage());
    Outcome outcome = failedAttempt(sessionId, Outcome.REJECTED);
    if (outcome == Outcome.RETRY_LIMIT_REACHED) {
      announceSessionFailure(sessionId);
    }
    return new Result(outcome, kindName(result.getFailure().getKind()));
  }

  /** FR-FAIL-003: only the game's own failures name a kind; infrastructure ones do not. */
  private static String kindName(GameFailureKind kind) {
    return kind.name().replaceFirst("^GAME_FAILURE_KIND_", "");
  }

  private Result infrastructure(Outcome outcome, UUID sessionId) {
    if (outcome == Outcome.RETRY_LIMIT_REACHED) {
      announceSessionFailure(sessionId);
    }
    return new Result(outcome, null);
  }

  /**
   * FR-FAIL-005: the retry limit was reached, so the session fails back to Lobby. A failure here
   * must not turn the input's own outcome into an error: {@code failcount:} stays at the limit, so
   * the next failed attempt announces again, and detach is idempotent.
   */
  private void announceSessionFailure(UUID sessionId) {
    try {
      sessionFailure.onRetryLimitReached(sessionId);
    } catch (RuntimeException e) {
      log.error("Session failure of sessionId={} could not be detached", sessionId, e);
    }
  }

  /**
   * FR-EFF-006: effects after {@code game_over} were delivered first. A failure here must not turn
   * a delivered result into a failed input: the replay of this sequence re-announces the end, and
   * detach is idempotent.
   */
  private void announceGameOver(UUID sessionId, GameOutcome outcome) {
    LifecycleLog.info(
        log,
        "Game ended",
        "sessionId",
        sessionId,
        "outcome",
        outcome.getDraw() ? "DRAW" : "DECIDED",
        "winningTeamIds",
        outcome.getTeamResultsList().stream()
            .filter(t -> t.getPlacement() == 1)
            .map(t -> t.getTeamId())
            .toList());
    recordOutcome(sessionId, outcome);
    try {
      gameEnd.onGameOver(sessionId);
    } catch (RuntimeException e) {
      log.error("Game end of sessionId={} could not be detached", sessionId, e);
    }
  }

  /**
   * History and statistics ({@code flows/06} §C). Read before detach, which clears the roster. A
   * failure is logged, not thrown: the room must still return to Lobby, and a replay of the same
   * sequence finds the completion row already written or the roster already gone.
   */
  private void recordOutcome(UUID sessionId, GameOutcome outcome) {
    try {
      String gameKey = redis.opsForValue().get("gamekey:" + sessionId);
      if (gameKey == null) {
        log.warn("No game key for sessionId={}; outcome not recorded", sessionId);
        return;
      }
      int split = gameKey.indexOf(':');
      outcomeRecorder.record(
          new GameOutcomeReport(
              sessionId,
              UUID.fromString(gameKey.substring(0, split)),
              gameKey.substring(split + 1),
              Instant.now(),
              outcome.getDraw(),
              outcome.getTeamResultsList().stream()
                  .map(
                      t ->
                          new GameOutcomeReport.TeamOutcome(
                              t.getTeamId(), t.getPlacement(), t.getStatsMap()))
                  .toList(),
              outcome.getPlayerResultsList().stream()
                  .map(p -> new GameOutcomeReport.PlayerOutcome(p.getPlayerId(), p.getStatsMap()))
                  .toList(),
              startingRoster(sessionId)));
    } catch (RuntimeException e) {
      log.error("Outcome of sessionId={} could not be recorded", sessionId, e);
    }
  }

  /**
   * The players the session started with ({@code roster:{sessionId}}), so a player who has since
   * left the room still counts as one of the session's; the room's current members only if the key
   * is gone.
   */
  private @Nullable SessionRoster startingRoster(UUID sessionId) {
    Map<Object, Object> stored = redis.opsForHash().entries("roster:" + sessionId);
    if (stored.isEmpty()) {
      return rosters.rosterOf(sessionId).orElse(null);
    }
    Map<UUID, UUID> players = new HashMap<>();
    stored.forEach(
        (user, team) ->
            players.put(UUID.fromString(user.toString()), UUID.fromString(team.toString())));
    return new SessionRoster(players, Set.of());
  }

  /**
   * FR-ORCH-005: the routed host is unreachable, so route the session to another. The next attempt
   * of the same sequence finds the session record in Redis and the new host restores it (§9.3). The
   * game key is what {@code gamekey:} exists to supply; with no other host the routing stays put
   * and the attempt is retried.
   */
  private void reassign(UUID sessionId, String failedInstance) {
    registry.quarantine(failedInstance, properties.leaseTtl());
    String gameKey = redis.opsForValue().get("gamekey:" + sessionId);
    int colon = gameKey == null ? -1 : gameKey.indexOf(':');
    if (colon < 0) {
      return;
    }
    Optional<String> replacement =
        registry.selectHost(
            gameKey.substring(0, colon), gameKey.substring(colon + 1), failedInstance);
    if (replacement.isEmpty()) {
      log.warn("No host to reassign sessionId={} from {}", sessionId, failedInstance);
      return;
    }
    redis.opsForValue().set("routing:" + sessionId, replacement.get(), HostRegistry.KEY_TTL);
    log.warn("Reassigned sessionId={} from {} to {}", sessionId, failedInstance, replacement.get());
  }

  /**
   * Fan out once per sequence, then write the failure-counter reset, the retained log entries and
   * the watermark in one round trip. The reset happens even when the fan-out throws (the result was
   * {@code OK}); the watermark moves only after a fan-out that completed.
   */
  private void deliverOnce(UUID sessionId, long sequence, ProcessResult result) {
    String delivered = redis.opsForValue().get("delivered:" + sessionId);
    Long advanceTo = null;
    List<GameLogStore.Entry> retained = List.of();
    try {
      if (delivered == null || sequence > Long.parseLong(delivered)) {
        retained = effects.deliver(sessionId, sequence, result.getEffectsList());
        advanceTo = sequence;
      }
    } finally {
      moveCommit.record(sessionId, advanceTo, retained);
    }
  }

  private Outcome onTransportFailure(
      UUID sessionId, String routedInstance, String address, StatusRuntimeException e) {
    Status.Code code = e.getStatus().getCode();
    log.warn("ProcessInput sessionId={} failed with {}", sessionId, code);
    return switch (code) {
      case NOT_FOUND -> Outcome.SESSION_NOT_FOUND;
      case FAILED_PRECONDITION, RESOURCE_EXHAUSTED -> Outcome.SESSION_FAILED;
      case UNAVAILABLE -> {
        channels.discard(address);
        reassign(sessionId, routedInstance);
        yield failedAttempt(sessionId, Outcome.RETRY);
      }
      default -> failedAttempt(sessionId, Outcome.RETRY);
    };
  }

  /** FR-FAIL-003: consecutive failures, reset on success; re-dispatch attempts count too. */
  private Outcome failedAttempt(UUID sessionId, Outcome ifBelowLimit) {
    Long count = redis.opsForValue().increment("failcount:" + sessionId);
    if (count != null && count >= retryLimit) {
      return Outcome.RETRY_LIMIT_REACHED;
    }
    return ifBelowLimit;
  }
}
