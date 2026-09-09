package io.turnbase.platform.room;

import static io.turnbase.platform.room.RoomCommandPayloads.requireInt;
import static io.turnbase.platform.room.RoomCommandPayloads.requireString;
import static io.turnbase.platform.room.RoomCommandPayloads.requireUuid;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.turnbase.platform.common.AuthenticatedUser;
import io.turnbase.platform.common.RoomUnavailableRetryException;
import java.security.Principal;
import java.util.Map;
import java.util.UUID;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Controller;

/**
 * {@code room-websocket.md} § Host Commands — {@code /app/room.{roomId}.host}. {@code IMP-M2-05}'s
 * own share of the table: {@code ADD_TEAM}, {@code REMOVE_TEAM}, {@code RESIZE_TEAM}, {@code
 * REASSIGN_PLAYER}, {@code ADD_AI_PLAYER}. {@code KICK_PLAYER}, {@code CHANGE_PARAMETERS}, {@code
 * START_GAME} and {@code TRANSFER_HOST} are {@code IMP-M2-06}'s — this item's read-list never names
 * the FR-HOST and FR-XFER requirements those commands realize, and an unrecognised {@code type}
 * here falls through to {@code UNKNOWN_COMMAND} until that item adds them to the same switch.
 *
 * <p>The channel-level {@code principal.id == room.hostId} guard {@code room-websocket.md} §
 * Authorization Model describes as a "fast-fail optimisation only" does not exist for this
 * destination ({@code IMP-M2-02} deferred it); every command below re-verifies host identity
 * itself, under {@link RoomEventExecutor}'s lock, which is the authoritative check the section
 * requires regardless of whether a fast-fail also exists.
 */
@Controller
class RoomHostCommandController {

  private final RoomService roomService;
  private final RoomCommandErrorSender errors;
  private final ObjectMapper objectMapper;

  RoomHostCommandController(
      RoomService roomService, RoomCommandErrorSender errors, ObjectMapper objectMapper) {
    this.roomService = roomService;
    this.errors = errors;
    this.objectMapper = objectMapper;
  }

  @MessageMapping("room.{roomId}.host")
  void handle(
      @DestinationVariable UUID roomId,
      @Payload RoomCommandEnvelope envelope,
      Principal principal) {
    UUID hostUserId = userIdOf(principal);
    try {
      dispatch(roomId, envelope, hostUserId);
    } catch (RoomCommandException e) {
      errors.send(
          principal,
          roomId,
          envelope.correlationId(),
          e.code(),
          e.message(),
          e.details().isEmpty() ? Map.of() : Map.of("details", e.details()));
    } catch (RoomUnavailableRetryException e) {
      errors.send(
          principal,
          roomId,
          envelope.correlationId(),
          "ROOM_UNAVAILABLE_RETRY",
          e.detail(),
          Map.of("retryAfterMs", e.retryAfterMs()));
    }
  }

  private void dispatch(UUID roomId, RoomCommandEnvelope envelope, UUID hostUserId) {
    JsonNode payload = envelope.payloadOrEmpty(objectMapper);
    switch (envelope.type()) {
      case "ADD_TEAM" -> {
        String templateName = requireString(payload, "templateName");
        int configuredSize = requireInt(payload, "configuredSize");
        roomService.addTeam(roomId, hostUserId, templateName, configuredSize);
      }
      case "REMOVE_TEAM" -> {
        UUID teamId = requireUuid(payload, "teamId");
        roomService.removeTeam(roomId, hostUserId, teamId);
      }
      case "RESIZE_TEAM" -> {
        UUID teamId = requireUuid(payload, "teamId");
        int newSize = requireInt(payload, "newSize");
        roomService.resizeTeam(roomId, hostUserId, teamId, newSize);
      }
      case "REASSIGN_PLAYER" -> {
        UUID playerId = requireUuid(payload, "playerId");
        UUID toTeamId = requireUuid(payload, "toTeamId");
        roomService.reassignPlayer(roomId, hostUserId, playerId, toTeamId);
      }
      case "ADD_AI_PLAYER" -> roomService.addAiPlayer(roomId, hostUserId);
      default ->
          throw new RoomCommandException(
              "UNKNOWN_COMMAND", "Unrecognised command type: " + envelope.type());
    }
  }

  private static UUID userIdOf(Principal principal) {
    if (principal instanceof AuthenticatedUser user) {
      return user.userId();
    }
    throw new IllegalStateException(
        "a STOMP command reached the handler with no AuthenticatedUser principal — the CONNECT"
            + " handshake must have set one");
  }
}
