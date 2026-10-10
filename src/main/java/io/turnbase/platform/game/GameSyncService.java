package io.turnbase.platform.game;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.turnbase.grpc.gamehost.v1.GameHostServiceGrpc;
import io.turnbase.grpc.gamehost.v1.PlayerRef;
import io.turnbase.grpc.gamehost.v1.ProcessStatus;
import io.turnbase.grpc.gamehost.v1.ResyncRequest;
import io.turnbase.grpc.gamehost.v1.ResyncResponse;
import io.turnbase.grpc.gamehost.v1.ViewerRole;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The read-only catch-up path ({@code websocket.md} § Catch-up: the sync flow): ask the routed Game
 * Host what one viewer sees now. It allocates no sequence, writes no key and moves no watermark,
 * and a failure here never counts toward the retry limit (FR-RESP-005) — it fails the attaching
 * client only. The Game Host serves the call under its own per-session single-flight lock, which is
 * what makes {@code asOfSequence} truthful.
 */
@Service
public class GameSyncService {

  private static final Logger log = LoggerFactory.getLogger(GameSyncService.class);

  /** What {@code GAME_SYNC} carries besides the init context. */
  public record Snapshot(long asOfSequence, JsonNode view, List<GameLogStore.Entry> log) {}

  /** The viewer could not be given a snapshot; the session itself is unaffected. */
  public static class ResyncFailedException extends RuntimeException {
    public ResyncFailedException(String detail) {
      super(detail);
    }
  }

  private final HostRegistry registry;
  private final GameHostChannels channels;
  private final StringRedisTemplate redis;
  private final OrchestrationProperties properties;
  private final GameLogStore logStore;
  private final JsonMapper objectMapper;

  GameSyncService(
      HostRegistry registry,
      GameHostChannels channels,
      StringRedisTemplate redis,
      OrchestrationProperties properties,
      GameLogStore logStore,
      JsonMapper objectMapper) {
    this.registry = registry;
    this.channels = channels;
    this.redis = redis;
    this.properties = properties;
    this.logStore = logStore;
    this.objectMapper = objectMapper;
  }

  /**
   * @param viewer the player to compose the view for; {@code null} for a spectator
   * @throws ResyncFailedException when no host can answer or the game's {@code onResync} failed
   */
  public Snapshot sync(UUID sessionId, @Nullable PlayerRef viewer) {
    String instanceId = redis.opsForValue().get("routing:" + sessionId);
    Optional<String> address =
        instanceId == null ? Optional.empty() : registry.addressOf(instanceId);
    if (address.isEmpty()) {
      throw new ResyncFailedException("no host serves session " + sessionId);
    }
    ResyncRequest.Builder request =
        ResyncRequest.newBuilder()
            .setSessionId(sessionId.toString())
            .setRole(
                viewer == null ? ViewerRole.VIEWER_ROLE_SPECTATOR : ViewerRole.VIEWER_ROLE_PLAYER);
    if (viewer != null) {
      request.setViewer(viewer);
    }
    ResyncResponse response;
    try {
      response =
          GameHostServiceGrpc.newBlockingStub(channels.forAddress(address.get()))
              .withDeadlineAfter(properties.dispatchDeadline().toMillis(), TimeUnit.MILLISECONDS)
              .resync(request.build());
    } catch (StatusRuntimeException e) {
      if (e.getStatus().getCode() == Status.Code.UNAVAILABLE) {
        channels.discard(address.get());
      }
      throw new ResyncFailedException("Resync failed with " + e.getStatus().getCode());
    }
    if (response.getStatus() != ProcessStatus.PROCESS_STATUS_OK) {
      // The message is game-supplied: logged, never sent to a client.
      log.warn(
          "Resync failure sessionId={} kind={} message={}",
          sessionId,
          response.getFailure().getKind(),
          response.getFailure().getMessage());
      throw new ResyncFailedException("onResync failed");
    }
    return new Snapshot(response.getAsOfSequence(), view(response), logStore.read(sessionId));
  }

  private JsonNode view(ResyncResponse response) {
    if (response.getView().isEmpty()) {
      return objectMapper.createObjectNode();
    }
    try {
      JsonNode view = objectMapper.readTree(response.getView().toByteArray());
      return view == null || view.isNull() ? objectMapper.createObjectNode() : view;
    } catch (JacksonException e) {
      throw new ResyncFailedException("onResync returned a view that is not JSON");
    }
  }
}
