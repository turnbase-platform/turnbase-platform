package io.turnbase.platform.game;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.turnbase.grpc.gamehost.v1.GameHostServiceGrpc;
import io.turnbase.grpc.gamehost.v1.TerminateSessionRequest;
import io.turnbase.grpc.gamehost.v1.TerminationReason;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * {@code room-lifecycle.md} §4 {@code terminateAndCleanup} steps (a) and (b): {@code
 * TerminateSession} against the host named by {@code routing:{sessionId}} with the protocol's 2 s
 * deadline, then deletion of the whole Redis key family. The host's answer is idempotent, so a
 * re-drive of a teardown that in fact succeeded is safe.
 */
@Component
public class SessionTerminationClient {

  private static final Logger log = LoggerFactory.getLogger(SessionTerminationClient.class);

  /** {@code orchestration-gamehost-protocol.md}: distinct from the dispatch deadline. */
  static final Duration DEADLINE = Duration.ofSeconds(2);

  private final HostRegistry registry;
  private final GameHostChannels channels;
  private final StringRedisTemplate redis;

  SessionTerminationClient(
      HostRegistry registry, GameHostChannels channels, StringRedisTemplate redis) {
    this.registry = registry;
    this.channels = channels;
    this.redis = redis;
  }

  /**
   * @param reason the platform's detach reason: {@code GAME_OVER} and {@code RETRY_LIMIT} map onto
   *     the proto enum of the same name, every closure reason onto {@code ROOM_CLOSED}
   * @return {@code true} once the host confirmed (or there is no host left to confirm) and the key
   *     family is gone; {@code false} leaves the teardown owed for the sweep. Never throws.
   */
  public boolean terminate(UUID sessionId, String reason) {
    try {
      String instanceId = redis.opsForValue().get("routing:" + sessionId);
      if (instanceId != null) {
        Optional<String> address = registry.addressOf(instanceId);
        if (address.isPresent() && !callTerminate(address.get(), sessionId, reason)) {
          return false;
        }
        // No registry entry: the host deregistered or expired, and took its contexts with it.
      }
      deleteKeyFamily(sessionId);
      return true;
    } catch (RuntimeException e) {
      log.warn("TerminateSession sessionId={} failed: {}", sessionId, e.toString());
      return false;
    }
  }

  private boolean callTerminate(String address, UUID sessionId, String reason) {
    try {
      GameHostServiceGrpc.newBlockingStub(channels.forAddress(address))
          .withDeadlineAfter(DEADLINE.toMillis(), TimeUnit.MILLISECONDS)
          .terminateSession(
              TerminateSessionRequest.newBuilder()
                  .setSessionId(sessionId.toString())
                  .setReason(toProto(reason))
                  .build());
      return true;
    } catch (StatusRuntimeException e) {
      if (e.getStatus().getCode() == Status.Code.UNAVAILABLE) {
        channels.discard(address);
      }
      log.warn(
          "TerminateSession sessionId={} to {} failed with {}",
          sessionId,
          address,
          e.getStatus().getCode());
      return false;
    }
  }

  static TerminationReason toProto(String reason) {
    return switch (reason.toUpperCase(java.util.Locale.ROOT)) {
      case "GAME_OVER" -> TerminationReason.TERMINATION_REASON_GAME_OVER;
      case "RETRY_LIMIT" -> TerminationReason.TERMINATION_REASON_RETRY_LIMIT;
      default -> TerminationReason.TERMINATION_REASON_ROOM_CLOSED;
    };
  }

  private void deleteKeyFamily(UUID sessionId) {
    redis.delete(
        List.of(
            "session:" + sessionId,
            "routing:" + sessionId,
            "gamekey:" + sessionId,
            "roster:" + sessionId,
            "queue:" + sessionId,
            "seq:" + sessionId,
            "delivered:" + sessionId,
            "failcount:" + sessionId,
            "gamelog:" + sessionId));
  }
}
