package io.turnbase.platform.room;

import io.turnbase.grpc.gamehost.v1.PlayerLeftInput;
import io.turnbase.grpc.gamehost.v1.PlayerLeftReason;
import io.turnbase.grpc.gamehost.v1.PlayerRef;
import io.turnbase.grpc.gamehost.v1.ProcessInputRequest;
import io.turnbase.platform.game.GameInputService;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The real {@link GameInputDispatcher}: turns a {@code player_left} outbox payload into {@code
 * ProcessInput} and maps {@link GameInputService.Outcome} onto the outbox's ack/retry contract
 * ({@code data-model.md} §4.2, including its conditional {@code NOT_FOUND}).
 */
@Component
class GameInputDispatcherImpl implements GameInputDispatcher {

  private static final Logger log = LoggerFactory.getLogger(GameInputDispatcherImpl.class);

  private final GameInputService inputs;
  private final RoomRepository repository;
  private final JsonMapper objectMapper;

  GameInputDispatcherImpl(
      GameInputService inputs, RoomRepository repository, JsonMapper objectMapper) {
    this.inputs = inputs;
    this.repository = repository;
    this.objectMapper = objectMapper;
  }

  @Override
  public long allocateSequence(UUID sessionId) {
    return inputs.allocateSequence(sessionId);
  }

  @Override
  public boolean dispatch(long outboxRowId, UUID sessionId, long sequence, String payloadJson) {
    ProcessInputRequest request;
    try {
      request = toRequest(sessionId, sequence, payloadJson);
    } catch (Exception e) {
      log.error("outbox row {} has an unreadable payload; dropping", outboxRowId, e);
      return true;
    }
    GameInputService.Outcome outcome = inputs.process(request);
    return switch (outcome) {
      case DELIVERED, REJECTED -> true;
      case RETRY_LIMIT_REACHED -> {
        // Already detached to Lobby by GameInputService; the input itself is spent.
        log.error("sessionId={} reached the retry limit on outbox row {}", sessionId, outboxRowId);
        yield true;
      }
      // Terminal only while a room still claims the session (data-model.md §4.2).
      case SESSION_NOT_FOUND -> !repository.sessionAttached(sessionId);
      case RETRY, SESSION_FAILED -> false;
    };
  }

  private ProcessInputRequest toRequest(UUID sessionId, long sequence, String payloadJson)
      throws Exception {
    JsonNode payload = objectMapper.readTree(payloadJson);
    PlayerLeftReason reason =
        switch (payload.path("reason").asText()) {
          case "kicked" -> PlayerLeftReason.PLAYER_LEFT_REASON_KICKED;
          case "grace_period_expired" -> PlayerLeftReason.PLAYER_LEFT_REASON_GRACE_PERIOD_EXPIRED;
          default -> PlayerLeftReason.PLAYER_LEFT_REASON_LEFT;
        };
    return ProcessInputRequest.newBuilder()
        .setSessionId(sessionId.toString())
        .setSequence(sequence)
        .setPlayerLeft(
            PlayerLeftInput.newBuilder()
                .setPlayer(
                    PlayerRef.newBuilder()
                        .setPlayerId(payload.path("playerId").asText())
                        .setDisplayName(payload.path("displayName").asText())
                        .setTeamId(payload.path("teamId").asText()))
                .setReason(reason))
        .build();
  }
}
