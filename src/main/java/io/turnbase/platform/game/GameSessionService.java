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
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
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

  /**
   * @throws GameSessionCreationException with {@link GameSessionCreationException.Reason} telling
   *     the caller which of the two documented outcomes occurred; every Orchestration key written
   *     here is deleted again before it is thrown.
   */
  public void create(CreateSessionCommand command) {
    String sessionId = command.sessionId().toString();
    String instanceId =
        registry
            .selectHost(command.gameId().toString(), command.gameVersion())
            .orElseThrow(
                () ->
                    new GameSessionCreationException(
                        GameSessionCreationException.Reason.NO_HOST_AVAILABLE, "no eligible host"));
    Optional<String> address = registry.addressOf(instanceId);
    if (address.isEmpty()) {
      throw new GameSessionCreationException(
          GameSessionCreationException.Reason.NO_HOST_AVAILABLE, "host entry vanished");
    }

    writeKeys(sessionId, instanceId, command.gameId() + ":" + command.gameVersion());
    ProcessResult initialEffects;
    try {
      CreateSessionResponse response = callCreateSession(address.get(), command);
      initialEffects = response.getResult();
      if (response.getResult().getStatus() != ProcessStatus.PROCESS_STATUS_OK) {
        throw new GameSessionCreationException(
            GameSessionCreationException.Reason.SESSION_CREATION_FAILED,
            "onInit failed: " + response.getResult().getFailure().getKind());
      }
    } catch (RuntimeException e) {
      deleteKeys(sessionId);
      log.warn("Session creation failed sessionId={} instanceId={}", sessionId, instanceId, e);
      if (e instanceof GameSessionCreationException) {
        throw e;
      }
      throw new GameSessionCreationException(
          GameSessionCreationException.Reason.SESSION_CREATION_FAILED, e.toString());
    }
    log.info("Session created sessionId={} instanceId={}", sessionId, instanceId);
    try {
      effects.deliverInitial(
          command.sessionId(), initialEffects.getEffectsList(), rosterOf(command));
    } catch (RuntimeException e) {
      // The session exists; a fan-out fault must not roll the room back to Lobby.
      log.error("Initial effects of sessionId={} not delivered", sessionId, e);
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
  private void writeKeys(String sessionId, String instanceId, String gameKey) {
    var ttl = HostRegistry.KEY_TTL;
    redis.opsForValue().set("routing:" + sessionId, instanceId, ttl);
    redis.opsForValue().set("gamekey:" + sessionId, gameKey, ttl);
    redis.opsForValue().set("seq:" + sessionId, "0", ttl);
    redis.opsForValue().set("delivered:" + sessionId, "0", ttl);
    redis.opsForValue().set("failcount:" + sessionId, "0", ttl);
    // queue:{sessionId} is a list and cannot exist empty; the first enqueue creates it.
  }

  private void deleteKeys(String sessionId) {
    redis.delete(
        List.of(
            "routing:" + sessionId,
            "gamekey:" + sessionId,
            "queue:" + sessionId,
            "seq:" + sessionId,
            "delivered:" + sessionId,
            "failcount:" + sessionId));
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
