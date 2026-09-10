package io.turnbase.platform.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.turnbase.platform.common.AuthenticatedUser;
import io.turnbase.platform.websocket.SubscriptionDecision;
import io.turnbase.platform.websocket.WebSocketConnectionRegistry;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

/**
 * {@code websocket.md} § Authorization / {@code room-websocket.md} § Authorization Model, applied
 * to {@code /topic/room.{roomId}} only, plus the socket-severing side of {@code
 * room.RoomOwnershipLostEvent}.
 */
class RoomSubscriptionAuthorizerTest {

  private final RoomRepository repository = mock(RoomRepository.class);
  private final RoomOwnershipService ownership = mock(RoomOwnershipService.class);
  private final WebSocketConnectionRegistry connectionRegistry =
      mock(WebSocketConnectionRegistry.class);
  private final RoomService roomService = mock(RoomService.class);
  private final RoomSubscriptionAuthorizer authorizer =
      new RoomSubscriptionAuthorizer(repository, ownership, connectionRegistry, roomService);

  private final AuthenticatedUser user =
      new AuthenticatedUser(UUID.randomUUID(), UUID.randomUUID());
  private final UUID roomId = UUID.randomUUID();

  @Test
  void supportsOnlyTheBareRoomTopicNotChatOrTeamSubTopics() {
    assertThat(authorizer.supports("/topic/room." + roomId)).isTrue();
    assertThat(authorizer.supports("/topic/room." + roomId + ".chat")).isFalse();
    assertThat(authorizer.supports("/topic/room." + roomId + ".team." + UUID.randomUUID()))
        .isFalse();
    assertThat(authorizer.supports("/topic/game." + roomId)).isFalse();
  }

  @Test
  void aNonMemberIsSilentlyDropped() {
    when(repository.isCurrentParticipant(roomId, user.userId())).thenReturn(false);

    SubscriptionDecision decision =
        authorizer.authorize(user, "/topic/room." + roomId, "stomp-session-1");

    assertThat(decision).isInstanceOf(SubscriptionDecision.SilentlyDrop.class);
    verify(ownership, never()).claimWithRetry(any());
  }

  @Test
  void aMemberOnAPodThatOwnsTheRoomIsAllowed() {
    when(repository.isCurrentParticipant(roomId, user.userId())).thenReturn(true);
    when(ownership.claimWithRetry(roomId))
        .thenReturn(new RoomOwnershipService.ClaimOutcome.AlreadyMine(1L));

    SubscriptionDecision decision =
        authorizer.authorize(user, "/topic/room." + roomId, "stomp-session-1");

    assertThat(decision).isInstanceOf(SubscriptionDecision.Allow.class);
  }

  @Test
  void aMemberOnAPodThatDoesNotOwnTheRoomIsToldToRetry() {
    when(repository.isCurrentParticipant(roomId, user.userId())).thenReturn(true);
    when(ownership.claimWithRetry(roomId))
        .thenReturn(new RoomOwnershipService.ClaimOutcome.Redirect("some-other-pod"));

    SubscriptionDecision decision =
        authorizer.authorize(user, "/topic/room." + roomId, "stomp-session-1");

    assertThat(decision).isInstanceOf(SubscriptionDecision.RejectWithError.class);
    assertThat(((SubscriptionDecision.RejectWithError) decision).code())
        .isEqualTo("ROOM_UNAVAILABLE_RETRY");
  }

  @Test
  void roomOwnershipLostClosesExactlyTheSessionsTrackedForThatRoom() {
    when(repository.isCurrentParticipant(roomId, user.userId())).thenReturn(true);
    when(ownership.claimWithRetry(roomId))
        .thenReturn(new RoomOwnershipService.ClaimOutcome.AlreadyMine(1L));
    authorizer.authorize(user, "/topic/room." + roomId, "stomp-session-1");
    authorizer.authorize(user, "/topic/room." + roomId, "stomp-session-2");

    authorizer.onRoomOwnershipLost(new RoomOwnershipLostEvent(roomId, "test"));

    verify(connectionRegistry).closeSession(eq("stomp-session-1"), anyString(), anyString());
    verify(connectionRegistry).closeSession(eq("stomp-session-2"), anyString(), anyString());
  }

  @Test
  void aRoomOwnershipLostEventForAnUntrackedRoomClosesNothing() {
    authorizer.onRoomOwnershipLost(new RoomOwnershipLostEvent(UUID.randomUUID(), "test"));

    verify(connectionRegistry, never()).closeSession(anyString(), anyString(), anyString());
  }

  @Test
  void aSessionDisconnectStopsTrackingThatSessionAgainstFutureOwnershipLoss() {
    when(repository.isCurrentParticipant(roomId, user.userId())).thenReturn(true);
    when(ownership.claimWithRetry(roomId))
        .thenReturn(new RoomOwnershipService.ClaimOutcome.AlreadyMine(1L));
    authorizer.authorize(user, "/topic/room." + roomId, "stomp-session-1");

    authorizer.onSessionDisconnect(disconnectEvent("stomp-session-1"));
    authorizer.onRoomOwnershipLost(new RoomOwnershipLostEvent(roomId, "test"));

    verify(connectionRegistry, never()).closeSession(anyString(), anyString(), anyString());
  }

  @Test
  void aGenuineSessionDisconnectFeedsTheDisconnectHandler() {
    when(repository.isCurrentParticipant(roomId, user.userId())).thenReturn(true);
    when(ownership.claimWithRetry(roomId))
        .thenReturn(new RoomOwnershipService.ClaimOutcome.AlreadyMine(1L));
    authorizer.authorize(user, "/topic/room." + roomId, "stomp-session-1");

    authorizer.onSessionDisconnect(disconnectEvent("stomp-session-1"));

    verify(roomService).onDisconnect(roomId, user.userId());
  }

  @Test
  void anInstanceInitiatedCloseNeverReachesTheDisconnectHandler() {
    when(repository.isCurrentParticipant(roomId, user.userId())).thenReturn(true);
    when(ownership.claimWithRetry(roomId))
        .thenReturn(new RoomOwnershipService.ClaimOutcome.AlreadyMine(1L));
    authorizer.authorize(user, "/topic/room." + roomId, "stomp-session-1");

    // room-lifecycle.md §1.6: the session was already tagged and severed before the transport
    // layer's own async SessionDisconnectEvent fires for it.
    authorizer.onRoomOwnershipLost(new RoomOwnershipLostEvent(roomId, "test"));
    authorizer.onSessionDisconnect(disconnectEvent("stomp-session-1"));

    verify(roomService, never()).onDisconnect(any(), any());
  }

  private static SessionDisconnectEvent disconnectEvent(String stompSessionId) {
    return new SessionDisconnectEvent(
        new Object(),
        MessageBuilder.withPayload(new byte[0]).build(),
        stompSessionId,
        CloseStatus.NORMAL);
  }
}
