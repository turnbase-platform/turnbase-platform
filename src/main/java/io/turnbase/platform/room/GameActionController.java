package io.turnbase.platform.room;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.ByteString;
import io.turnbase.grpc.gamehost.v1.ActionInput;
import io.turnbase.grpc.gamehost.v1.PlayerRef;
import io.turnbase.platform.common.AuthenticatedUser;
import io.turnbase.platform.game.GameInputService;
import java.security.Principal;
import java.util.LinkedHashMap;
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
 * {@code websocket.md} § {@code SUBMIT_ACTION} — {@code /app/game.{sessionId}.action}, the acting
 * player's path into {@link GameInputService}. The handler-level admission is authoritative ({@code
 * 03-game-turn.md} §B): the session must be the active session of an In-Progress room and the
 * sender a current, non-spectator player of it, all read from durable room state at handler time.
 * The {@code PlayerRef} handed to the game is built here from that state — a client-supplied
 * identity in the payload is opaque game data and is never read (FR-FA-002, NFR-SEC-001).
 *
 * <p>Lives in the {@code room} package because the participant tables are its internals. What
 *
 * <p>A failed action is never re-dispatched and its sequence is burned (GAME-I3); the acting player
 * is told with a unicast {@code ACTION_REJECTED} carrying the failure <em>kind</em>, never the
 * game's message. Infrastructure outcomes drop the input the same way, with kind {@code
 * UNAVAILABLE}.
 */
@Controller
class GameActionController {

  private static final Logger log = LoggerFactory.getLogger(GameActionController.class);

  private final RoomRepository repository;
  private final GameInputService inputs;
  private final SimpMessagingTemplate messagingTemplate;
  private final ObjectMapper objectMapper;

  GameActionController(
      RoomRepository repository,
      GameInputService inputs,
      SimpMessagingTemplate messagingTemplate,
      ObjectMapper objectMapper) {
    this.repository = repository;
    this.inputs = inputs;
    this.messagingTemplate = messagingTemplate;
    this.objectMapper = objectMapper;
  }

  @MessageMapping("game.{sessionId}.action")
  void handle(
      @DestinationVariable UUID sessionId,
      @Payload RoomCommandEnvelope envelope,
      Principal principal) {
    if (!(principal instanceof AuthenticatedUser user)) {
      throw new IllegalStateException(
          "a STOMP command reached the handler with no AuthenticatedUser principal");
    }
    if (!"SUBMIT_ACTION".equals(envelope.type())) {
      error(
          principal,
          sessionId,
          envelope,
          "UNKNOWN_COMMAND",
          "Unrecognised command type: " + envelope.type());
      return;
    }
    // One code for "no such active session" and "not in it": which sessions exist is not the
    // caller's to learn.
    RoomRepository.ActingParticipant actor =
        repository.actingParticipant(sessionId, user.userId()).orElse(null);
    if (actor == null) {
      error(principal, sessionId, envelope, "NOT_MEMBER", "You are not a player of this session.");
      return;
    }
    if (!"PLAYER".equals(actor.role()) || actor.teamId() == null) {
      error(
          principal, sessionId, envelope, "ROLE_NOT_ELIGIBLE", "Only players can submit actions.");
      return;
    }
    ByteString payload;
    try {
      payload =
          ByteString.copyFrom(
              objectMapper.writeValueAsBytes(envelope.payloadOrEmpty(objectMapper)));
    } catch (JsonProcessingException e) {
      error(principal, sessionId, envelope, "VALIDATION_FAILED", "The action payload is invalid.");
      return;
    }
    ActionInput action =
        ActionInput.newBuilder()
            .setPlayer(
                PlayerRef.newBuilder()
                    .setPlayerId(user.userId().toString())
                    .setDisplayName(actor.username())
                    .setTeamId(actor.teamId().toString()))
            .setPayload(payload)
            .build();
    GameInputService.Result result = inputs.submitAction(sessionId, action);
    if (result.outcome() != GameInputService.Outcome.DELIVERED) {
      log.warn("sessionId={} action from {} ended {}", sessionId, user.userId(), result.outcome());
      String kind = result.failureKind() == null ? "UNAVAILABLE" : result.failureKind();
      error(principal, sessionId, envelope, "ACTION_REJECTED", null, kind);
    }
  }

  private void error(
      Principal principal,
      UUID sessionId,
      RoomCommandEnvelope envelope,
      String code,
      @Nullable String message) {
    error(principal, sessionId, envelope, code, message, null);
  }

  private void error(
      Principal principal,
      UUID sessionId,
      RoomCommandEnvelope envelope,
      String code,
      @Nullable String message,
      @Nullable String kind) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("correlationId", envelope.correlationId());
    payload.put("code", code);
    if (message != null) {
      payload.put("message", message);
    }
    if (kind != null) {
      payload.put("kind", kind);
    }
    payload.put("sessionId", sessionId);
    messagingTemplate.convertAndSendToUser(
        principal.getName(), "/queue/errors", Map.of("type", "ERROR", "payload", payload));
  }
}
