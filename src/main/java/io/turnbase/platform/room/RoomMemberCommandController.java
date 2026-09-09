package io.turnbase.platform.room;

import static io.turnbase.platform.room.RoomCommandPayloads.requireBoolean;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.turnbase.platform.common.AuthenticatedUser;
import io.turnbase.platform.common.RoomUnavailableRetryException;
import java.security.Principal;
import java.util.Map;
import java.util.UUID;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

/**
 * {@code room-websocket.md} § Member Commands — {@code /app/room.{roomId}.member}. One handler
 * dispatches on the envelope's {@code type}, since STOMP routes by destination, not by a field
 * inside the frame body. Covers the whole Member Commands table: {@code TOGGLE_READY}, {@code
 * SWITCH_TO_SPECTATOR}, {@code SWITCH_TO_PLAYER}, {@code REQUEST_ROOM_STATE}.
 *
 * <p>Authorization is handler-level and authoritative ({@code room-websocket.md} § Authorization
 * Model): every command re-checks membership and role under {@link RoomEventExecutor}'s lock via
 * {@link RoomRepository}, rather than relying on a channel-level fast-fail that does not exist for
 * this destination ({@code IMP-M2-02} deferred it here).
 */
@Controller
class RoomMemberCommandController {

  private final RoomService roomService;
  private final SimpMessagingTemplate messagingTemplate;
  private final RoomCommandErrorSender errors;
  private final ObjectMapper objectMapper;

  RoomMemberCommandController(
      RoomService roomService,
      SimpMessagingTemplate messagingTemplate,
      RoomCommandErrorSender errors,
      ObjectMapper objectMapper) {
    this.roomService = roomService;
    this.messagingTemplate = messagingTemplate;
    this.errors = errors;
    this.objectMapper = objectMapper;
  }

  @MessageMapping("room.{roomId}.member")
  void handle(
      @DestinationVariable UUID roomId,
      @Payload RoomCommandEnvelope envelope,
      Principal principal) {
    UUID userId = userIdOf(principal);
    try {
      dispatch(roomId, envelope, principal, userId);
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

  private void dispatch(
      UUID roomId, RoomCommandEnvelope envelope, Principal principal, UUID userId) {
    switch (envelope.type()) {
      case "TOGGLE_READY" -> {
        boolean ready = requireBoolean(envelope.payloadOrEmpty(objectMapper), "ready");
        roomService.toggleReady(roomId, userId, ready);
      }
      case "SWITCH_TO_SPECTATOR" -> roomService.switchToSpectator(roomId, userId);
      case "SWITCH_TO_PLAYER" -> roomService.switchToPlayer(roomId, userId);
      case "REQUEST_ROOM_STATE" -> sendRoomState(roomId, principal, userId);
      default ->
          throw new RoomCommandException(
              "UNKNOWN_COMMAND", "Unrecognised command type: " + envelope.type());
    }
  }

  /** {@code room-websocket.md} § Snapshot Delivery — unicast, not broadcast. */
  private void sendRoomState(UUID roomId, Principal principal, UUID userId) {
    RoomRepository.RoomStateSnapshotWithVersion snapshot = roomService.roomState(roomId, userId);
    messagingTemplate.convertAndSendToUser(
        principal.getName(),
        "/queue/room-state",
        Map.of(
            "type", "ROOM_STATE", "version", snapshot.version(), "payload", snapshot.snapshot()));
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
