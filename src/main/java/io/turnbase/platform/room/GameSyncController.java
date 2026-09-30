package io.turnbase.platform.room;

import io.turnbase.grpc.gamehost.v1.PlayerRef;
import io.turnbase.platform.common.AuthenticatedUser;
import io.turnbase.platform.game.GameSessionService;
import io.turnbase.platform.game.GameSyncService;
import java.security.Principal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

/**
 * {@code websocket.md} § {@code REQUEST_GAME_SYNC} — {@code /app/game.{sessionId}.sync}. A
 * participant of an in-progress session, player or spectator, is sent one unicast {@code GAME_SYNC}
 * on {@code /user/queue/game}: the init context composed here from durable room state, the view the
 * game backend computes for that viewer ({@link GameSyncService}), and the retained game log.
 * Membership is re-read at handler time; the channel check is only a fast-fail (IMP-GATE-93).
 *
 * <p>Lives in the {@code room} package because the participant tables are its internals.
 */
@Controller
class GameSyncController {

  private static final Logger log = LoggerFactory.getLogger(GameSyncController.class);

  private final RoomRepository repository;
  private final GameSyncService sync;
  private final SimpMessagingTemplate messagingTemplate;

  GameSyncController(
      RoomRepository repository, GameSyncService sync, SimpMessagingTemplate messagingTemplate) {
    this.repository = repository;
    this.sync = sync;
    this.messagingTemplate = messagingTemplate;
  }

  @MessageMapping("game.{sessionId}.sync")
  void handle(
      @DestinationVariable UUID sessionId,
      @Payload RoomCommandEnvelope envelope,
      Principal principal) {
    if (!(principal instanceof AuthenticatedUser user)) {
      throw new IllegalStateException(
          "a STOMP command reached the handler with no AuthenticatedUser principal");
    }
    if (!"REQUEST_GAME_SYNC".equals(envelope.type())) {
      error(principal, sessionId, envelope, "UNKNOWN_COMMAND");
      return;
    }
    RoomRepository.ActingParticipant actor =
        repository.actingParticipant(sessionId, user.userId()).orElse(null);
    UUID roomId = actor == null ? null : repository.inProgressRoomOf(sessionId).orElse(null);
    if (actor == null || roomId == null) {
      error(principal, sessionId, envelope, "NOT_MEMBER");
      return;
    }
    boolean player = "PLAYER".equals(actor.role()) && actor.teamId() != null;
    PlayerRef self =
        PlayerRef.newBuilder()
            .setPlayerId(user.userId().toString())
            .setDisplayName(actor.username())
            .setTeamId(actor.teamId() == null ? "" : actor.teamId().toString())
            .build();
    GameSyncService.Snapshot snapshot;
    try {
      snapshot = sync.sync(sessionId, player ? self : null);
    } catch (GameSyncService.ResyncFailedException e) {
      log.warn("sessionId={} sync for {} failed: {}", sessionId, user.userId(), e.getMessage());
      error(principal, sessionId, envelope, "RESYNC_FAILED");
      return;
    }
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("init", init(sessionId, roomId, player, user.userId()));
    payload.put("view", snapshot.view());
    payload.put("log", snapshot.log());
    Map<String, Object> message = new LinkedHashMap<>();
    message.put("type", "GAME_SYNC");
    message.put("sessionId", sessionId);
    message.put("asOfSequence", snapshot.asOfSequence());
    message.put("correlationId", envelope.correlationId());
    message.put("payload", payload);
    messagingTemplate.convertAndSendToUser(principal.getName(), "/queue/game", message);
  }

  /** {@code frontend-gdk-api.md} §5's {@code InitContext}, in the GDK's {@code Player} shape. */
  private Map<String, Object> init(UUID sessionId, UUID roomId, boolean player, UUID userId) {
    GameSessionService.CreateSessionCommand command = repository.sessionCommand(roomId, sessionId);
    List<Map<String, Object>> roster = new ArrayList<>();
    List<Map<String, Object>> teams = new ArrayList<>();
    @Nullable Map<String, Object> self = null;
    for (GameSessionService.TeamSpec team : command.teams()) {
      List<Map<String, Object>> members = new ArrayList<>();
      for (GameSessionService.PlayerSpec p : team.players()) {
        Map<String, Object> entry = playerEntry(p.playerId(), p.displayName(), team.teamId());
        members.add(entry);
        roster.add(entry);
        if (p.playerId().equals(userId)) {
          self = entry;
        }
      }
      Map<String, Object> t = new LinkedHashMap<>();
      t.put("id", team.teamId());
      t.put("name", team.name());
      t.put("players", members);
      teams.add(t);
    }
    Map<String, Object> init = new LinkedHashMap<>();
    init.put("sessionId", sessionId);
    init.put("role", player ? "player" : "spectator");
    init.put("self", player ? self : null);
    init.put("roster", roster);
    init.put("teams", teams);
    return init;
  }

  private static Map<String, Object> playerEntry(UUID id, String displayName, UUID teamId) {
    Map<String, Object> entry = new LinkedHashMap<>();
    entry.put("id", id);
    entry.put("displayName", displayName);
    entry.put("teamId", teamId);
    return entry;
  }

  private void error(
      Principal principal, UUID sessionId, RoomCommandEnvelope envelope, String code) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("correlationId", envelope.correlationId());
    payload.put("code", code);
    payload.put("sessionId", sessionId);
    messagingTemplate.convertAndSendToUser(
        principal.getName(), "/queue/errors", Map.of("type", "ERROR", "payload", payload));
  }
}
