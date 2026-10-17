package io.turnbase.platform.game;

import com.google.protobuf.ByteString;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.turnbase.grpc.gamehost.v1.ContextKey;
import io.turnbase.grpc.gamehost.v1.CreateSessionRequest;
import io.turnbase.grpc.gamehost.v1.CreateSessionResponse;
import io.turnbase.grpc.gamehost.v1.GameHostServiceGrpc;
import io.turnbase.grpc.gamehost.v1.PlayerRef;
import io.turnbase.grpc.gamehost.v1.ProcessResult;
import io.turnbase.grpc.gamehost.v1.ProcessStatus;
import io.turnbase.grpc.gamehost.v1.Team;
import io.turnbase.platform.common.LifecycleLog;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * The Orchestration Service's session-creation half ({@code flows/02-room-to-game-start.md} §E
 * steps 6–8): choose a Game Host, write the Orchestration-owned Redis keys, and call {@code
 * CreateSession}. It never writes {@code session:{id}} — that record has one writer, the Game Host
 * (IR-14), and pre-writing it would satisfy {@code CreateSession}'s idempotency guard so {@code
 * onInit} never ran. It does not touch {@code room} either: persisting {@code active_session_id}
 * and broadcasting {@code GAME_STARTED} belong to the room, after this returns.
 */
@Service
public class GameSessionService {

  private static final Logger log = LoggerFactory.getLogger(GameSessionService.class);

  /** The first try and one more — never a loop: each try can cost a full dispatch deadline. */
  private static final int MAX_ATTEMPTS = 3;

  private static final Duration HOST_POLL = Duration.ofMillis(500);

  private final HostRegistry registry;
  private final GameHostChannels channels;
  private final StringRedisTemplate redis;
  private final OrchestrationProperties properties;
  private final EffectDelivery effects;

  GameSessionService(
      HostRegistry registry,
      GameHostChannels channels,
      StringRedisTemplate redis,
      OrchestrationProperties properties,
      EffectDelivery effects) {
    this.effects = effects;
    this.registry = registry;
    this.channels = channels;
    this.redis = redis;
    this.properties = properties;
  }

  /** As {@link #create(CreateSessionCommand, StartProgress)}, reporting nothing. */
  public void create(CreateSessionCommand command) {
    create(command, StartProgress.NONE);
  }

  /**
   * Chooses a host and has it boot the session, waiting for a host that is still starting and
   * trying again, up to three attempts in all, when one could not be reached or did not answer.
   *
   * <p>A host that holds {@code CreateSession} past the dispatch deadline is quarantined: its lease
   * and heartbeat are healthy by construction, so the lease cannot say it is wedged. {@code
   * UNAVAILABLE} is a connection the host closed (a restart behind a pooled channel), so the retry
   * dials afresh; {@code CreateSession} is idempotent per session id.
   *
   * @throws GameSessionCreationException with {@link GameSessionCreationException.Reason} telling
   *     the caller which outcome occurred; every Orchestration key written here is deleted again
   *     before it is thrown.
   */
  public void create(CreateSessionCommand command, StartProgress progress) {
    Set<String> failedHosts = new HashSet<>();
    GameSessionCreationException lastFailure = null;
    for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
      if (attempt > 1) {
        progress.report(StartPhase.RETRYING);
      }
      // A host that was quarantined is usually a host the supervisor is about to restart, and the
      // restart registers under a new instance id; so every attempt waits for one, not only the
      // first.
      Duration wait = properties.hostWait();
      SelectedHost host = awaitHost(command, failedHosts, wait, progress);
      try {
        dispatch(command, host, progress);
        return;
      } catch (HostFailure failure) {
        lastFailure = failure.asException();
        if (failure.quarantine()) {
          registry.quarantine(host.instanceId());
          failedHosts.add(host.instanceId());
        }
        if (!failure.retryable()) {
          throw lastFailure;
        }
      }
    }
    throw lastFailure;
  }

  private SelectedHost awaitHost(
      CreateSessionCommand command,
      Set<String> failedHosts,
      Duration wait,
      StartProgress progress) {
    long deadline = System.nanoTime() + wait.toNanos();
    boolean reported = false;
    while (true) {
      Optional<String> instanceId =
          registry.selectHost(command.gameId().toString(), command.gameVersion(), failedHosts);
      if (instanceId.isPresent()) {
        Optional<String> address = registry.addressOf(instanceId.get());
        if (address.isPresent()) {
          return new SelectedHost(instanceId.get(), address.get());
        }
      }
      if (System.nanoTime() >= deadline) {
        throw new GameSessionCreationException(
            failedHosts.isEmpty()
                ? GameSessionCreationException.Reason.NO_HOST_AVAILABLE
                : GameSessionCreationException.Reason.HOST_UNRESPONSIVE,
            "no eligible host");
      }
      if (!reported) {
        progress.report(StartPhase.WAITING_FOR_HOST);
        reported = true;
      }
      try {
        Thread.sleep(HOST_POLL.toMillis());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new GameSessionCreationException(
            GameSessionCreationException.Reason.NO_HOST_AVAILABLE, "interrupted");
      }
    }
  }

  private void dispatch(CreateSessionCommand command, SelectedHost host, StartProgress progress) {
    String sessionId = command.sessionId().toString();
    writeKeys(
        sessionId,
        host.instanceId(),
        command.gameId() + ":" + command.gameVersion(),
        rosterOf(command));
    progress.report(StartPhase.LOADING_GAME);
    ProcessResult initialEffects;
    try {
      CreateSessionResponse response = callCreateSession(host.address(), command);
      initialEffects = response.getResult();
      if (response.getResult().getStatus() != ProcessStatus.PROCESS_STATUS_OK) {
        throw new HostFailure(
            GameSessionCreationException.Reason.SESSION_CREATION_FAILED,
            "onInit failed: " + response.getResult().getFailure().getKind(),
            false,
            false);
      }
    } catch (StatusRuntimeException e) {
      deleteKeys(sessionId);
      log.warn(
          "Session creation failed sessionId={} instanceId={}", sessionId, host.instanceId(), e);
      Status.Code code = e.getStatus().getCode();
      if (code == Status.Code.DEADLINE_EXCEEDED) {
        throw new HostFailure(
            GameSessionCreationException.Reason.HOST_UNRESPONSIVE, e.toString(), true, true);
      }
      if (code == Status.Code.UNAVAILABLE) {
        throw new HostFailure(
            GameSessionCreationException.Reason.HOST_UNRESPONSIVE, e.toString(), false, true);
      }
      throw new HostFailure(
          GameSessionCreationException.Reason.SESSION_CREATION_FAILED, e.toString(), false, false);
    } catch (HostFailure e) {
      deleteKeys(sessionId);
      log.warn("Session creation failed sessionId={} instanceId={}", sessionId, host.instanceId());
      throw e;
    } catch (RuntimeException e) {
      deleteKeys(sessionId);
      log.warn(
          "Session creation failed sessionId={} instanceId={}", sessionId, host.instanceId(), e);
      throw new HostFailure(
          GameSessionCreationException.Reason.SESSION_CREATION_FAILED, e.toString(), false, false);
    }
    LifecycleLog.info(
        log,
        "Game started",
        "sessionId",
        sessionId,
        "gameId",
        command.gameId(),
        "instanceId",
        host.instanceId());
    try {
      effects.deliverInitial(
          command.sessionId(), initialEffects.getEffectsList(), rosterOf(command));
    } catch (RuntimeException e) {
      // The session exists; a fan-out fault must not roll the room back to Lobby.
      log.error("Initial effects of sessionId={} not delivered", sessionId, e);
    }
  }

  private record SelectedHost(String instanceId, String address) {}

  /**
   * One attempt's failure. {@code retryable}: another attempt may succeed; {@code quarantine}: the
   * host held the call past its deadline and is not routed to again until the quarantine expires.
   */
  private static final class HostFailure extends RuntimeException {

    private final GameSessionCreationException.Reason reason;
    private final boolean quarantine;
    private final boolean retryable;

    HostFailure(
        GameSessionCreationException.Reason reason,
        String detail,
        boolean quarantine,
        boolean retryable) {
      super(detail, null, false, false);
      this.reason = reason;
      this.quarantine = quarantine;
      this.retryable = retryable;
    }

    boolean quarantine() {
      return quarantine;
    }

    boolean retryable() {
      return retryable;
    }

    GameSessionCreationException asException() {
      return new GameSessionCreationException(reason, String.valueOf(getMessage()));
    }
  }

  private static SessionRoster rosterOf(CreateSessionCommand command) {
    Map<UUID, UUID> playerTeams = new HashMap<>();
    for (TeamSpec team : command.teams()) {
      for (PlayerSpec player : team.players()) {
        playerTeams.put(player.playerId(), team.teamId());
      }
    }
    return new SessionRoster(playerTeams, Set.of());
  }

  private CreateSessionResponse callCreateSession(String address, CreateSessionCommand command) {
    CreateSessionRequest.Builder request =
        CreateSessionRequest.newBuilder()
            .setSessionId(command.sessionId().toString())
            .setContext(
                ContextKey.newBuilder()
                    .setGameId(command.gameId().toString())
                    .setGameVersion(command.gameVersion()))
            .setParameters(ByteString.copyFrom(command.parametersJson(), StandardCharsets.UTF_8))
            .setEntryPointFqcn(command.entryPointFqcn());
    for (TeamSpec team : command.teams()) {
      Team.Builder t = Team.newBuilder().setTeamId(team.teamId().toString()).setName(team.name());
      for (PlayerSpec player : team.players()) {
        t.addPlayers(
            PlayerRef.newBuilder()
                .setPlayerId(player.playerId().toString())
                .setDisplayName(player.displayName())
                .setTeamId(team.teamId().toString()));
      }
      request.addTeams(t);
    }
    try {
      return GameHostServiceGrpc.newBlockingStub(channels.forAddress(address))
          .withDeadlineAfter(properties.dispatchDeadline().toMillis(), TimeUnit.MILLISECONDS)
          .createSession(request.build());
    } catch (StatusRuntimeException e) {
      if (e.getStatus().getCode() == Status.Code.UNAVAILABLE) {
        channels.discard(address);
      }
      throw e;
    }
  }

  /** {@code game-host-internals.md} §13: {@code routing:}, {@code seq:}, {@code delivered:}, … */
  private void writeKeys(
      String sessionId, String instanceId, String gameKey, SessionRoster roster) {
    var ttl = HostRegistry.KEY_TTL;
    redis.opsForValue().set("routing:" + sessionId, instanceId, ttl);
    redis.opsForValue().set("gamekey:" + sessionId, gameKey, ttl);
    redis.opsForValue().set("seq:" + sessionId, "0", ttl);
    redis.opsForValue().set("delivered:" + sessionId, "0", ttl);
    redis.opsForValue().set("failcount:" + sessionId, "0", ttl);
    // The players the session started with: the room's own rows lose a leaver, the history
    // recorded at game end must not (RR-03).
    Map<String, String> players = new HashMap<>();
    roster.playerTeams().forEach((user, team) -> players.put(user.toString(), team.toString()));
    redis.opsForHash().putAll("roster:" + sessionId, players);
    redis.expire("roster:" + sessionId, ttl);
    // queue:{sessionId} is a list and cannot exist empty; the first enqueue creates it.
  }

  private void deleteKeys(String sessionId) {
    redis.delete(
        List.of(
            "routing:" + sessionId,
            "gamekey:" + sessionId,
            "roster:" + sessionId,
            "queue:" + sessionId,
            "seq:" + sessionId,
            "delivered:" + sessionId,
            "failcount:" + sessionId,
            "gamelog:" + sessionId));
  }

  /** The room's final lobby state, resolved by the room (which owns those tables). */
  public record CreateSessionCommand(
      UUID sessionId,
      UUID gameId,
      String gameVersion,
      String entryPointFqcn,
      List<TeamSpec> teams,
      String parametersJson) {}

  public record TeamSpec(UUID teamId, String name, List<PlayerSpec> players) {}

  public record PlayerSpec(UUID playerId, String displayName) {}

  /** {@code room-websocket.md}'s {@code GAME_START_FAILED} reasons (IMP-GATE-88). */
  public static class GameSessionCreationException extends RuntimeException {

    public enum Reason {
      NO_HOST_AVAILABLE,
      HOST_UNRESPONSIVE,
      SESSION_CREATION_FAILED
    }

    private final Reason reason;

    public GameSessionCreationException(Reason reason, String detail) {
      super(detail);
      this.reason = reason;
    }

    public Reason reason() {
      return reason;
    }
  }
}
