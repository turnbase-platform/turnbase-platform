package io.turnbase.platform.game;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.turnbase.grpc.gamehost.v1.ActionInput;
import io.turnbase.grpc.gamehost.v1.GameHostServiceGrpc;
import io.turnbase.grpc.gamehost.v1.ProcessInputRequest;
import io.turnbase.grpc.gamehost.v1.ProcessResult;
import io.turnbase.grpc.gamehost.v1.ProcessStatus;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
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

  private final HostRegistry registry;
  private final GameHostChannels channels;
  private final StringRedisTemplate redis;
  private final OrchestrationProperties properties;
  private final EffectDelivery effects;
  private final int retryLimit;
  private final Map<UUID, ReentrantLock> submitLocks = new ConcurrentHashMap<>();

  GameInputService(
      HostRegistry registry,
      GameHostChannels channels,
      StringRedisTemplate redis,
      OrchestrationProperties properties,
      EffectDelivery effects,
      @Value("${turnbase.gamehost.retry-limit:3}") int retryLimit) {
    this.registry = registry;
    this.channels = channels;
    this.redis = redis;
    this.properties = properties;
    this.effects = effects;
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
  public Outcome submitAction(UUID sessionId, ActionInput action) {
    ReentrantLock lock = submitLocks.computeIfAbsent(sessionId, id -> new ReentrantLock());
    lock.lock();
    try {
      return process(
          ProcessInputRequest.newBuilder()
              .setSessionId(sessionId.toString())
              .setSequence(allocateSequence(sessionId))
              .setAction(action)
              .build());
    } finally {
      lock.unlock();
    }
  }

  /** {@code request.sequence} must have been allocated by {@link #allocateSequence}. */
  public Outcome process(ProcessInputRequest request) {
    UUID sessionId = UUID.fromString(request.getSessionId());
    String instanceId = redis.opsForValue().get("routing:" + sessionId);
    if (instanceId == null) {
      return Outcome.SESSION_NOT_FOUND;
    }
    Optional<String> address = registry.addressOf(instanceId);
    if (address.isEmpty()) {
      log.warn("Host {} of sessionId={} is gone; input left for retry", instanceId, sessionId);
      return failedAttempt(sessionId, Outcome.RETRY);
    }
    ProcessResult result;
    try {
      result =
          GameHostServiceGrpc.newBlockingStub(channels.forAddress(address.get()))
              .withDeadlineAfter(properties.dispatchDeadline().toMillis(), TimeUnit.MILLISECONDS)
              .processInput(request)
              .getResult();
    } catch (StatusRuntimeException e) {
      return onTransportFailure(sessionId, address.get(), e);
    }
    if (result.getStatus() == ProcessStatus.PROCESS_STATUS_OK) {
      redis.opsForValue().set("failcount:" + sessionId, "0", HostRegistry.KEY_TTL);
      deliverOnce(sessionId, request.getSequence(), result);
      return Outcome.DELIVERED;
    }
    // Detail goes to the log only: it is game-supplied text and never reaches a client.
    log.warn(
        "Game failure sessionId={} sequence={} kind={} message={}",
        sessionId,
        request.getSequence(),
        result.getFailure().getKind(),
        result.getFailure().getMessage());
    return failedAttempt(sessionId, Outcome.REJECTED);
  }

  private void deliverOnce(UUID sessionId, long sequence, ProcessResult result) {
    String delivered = redis.opsForValue().get("delivered:" + sessionId);
    if (delivered != null && sequence <= Long.parseLong(delivered)) {
      return;
    }
    effects.deliver(sessionId, sequence, result.getEffectsList());
    redis
        .opsForValue()
        .set("delivered:" + sessionId, Long.toString(sequence), HostRegistry.KEY_TTL);
  }

  private Outcome onTransportFailure(UUID sessionId, String address, StatusRuntimeException e) {
    Status.Code code = e.getStatus().getCode();
    log.warn("ProcessInput sessionId={} failed with {}", sessionId, code);
    return switch (code) {
      case NOT_FOUND -> Outcome.SESSION_NOT_FOUND;
      case FAILED_PRECONDITION, RESOURCE_EXHAUSTED -> Outcome.SESSION_FAILED;
      case UNAVAILABLE -> {
        channels.discard(address);
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
