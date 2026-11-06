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

  private final RoomRepository rooms = mock(RoomRepository.class);
  private final RoomPresenceRepository presence = mock(RoomPresenceRepository.class);
  private final RoomOwnershipService ownership = mock(RoomOwnershipService.class);
  private final WebSocketConnectionRegistry connectionRegistry =
      mock(WebSocketConnectionRegistry.class);
  private final RoomService roomService = mock(RoomService.class);
  private final RoomSubscriptionAuthorizer authorizer =
      new RoomSubscriptionAuthorizer(presence, rooms, ownership, connectionRegistry, roomService);

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
    when(rooms.isCurrentParticipant(roomId, user.userId())).thenReturn(false);

    SubscriptionDecision decision =
        authorizer.authorize(user, "/topic/room." + roomId, "stomp-session-1");

    assertThat(decision).isInstanceOf(SubscriptionDecision.SilentlyDrop.class);
    verify(ownership, never()).claimWithRetry(any());
  }

  @Test
  void aMemberOnAPodThatOwnsTheRoomIsAllowed() {
    when(rooms.isCurrentParticipant(roomId, user.userId())).thenReturn(true);
    when(ownership.claimWithRetry(roomId))
        .thenReturn(new RoomOwnershipService.ClaimOutcome.AlreadyMine(1L));

    SubscriptionDecision decision =
        authorizer.authorize(user, "/topic/room." + roomId, "stomp-session-1");

    assertThat(decision).isInstanceOf(SubscriptionDecision.Allow.class);
  }

  /** {@code IMP-M2-08}, {@code room-lifecycle.md} §3.2: first subscribe resolves any reconnect. */
  @Test
  void aMembersSubscribeResolvesTheReconnectExactlyOnce() {
    when(rooms.isCurrentParticipant(roomId, user.userId())).thenReturn(true);
    when(ownership.claimWithRetry(roomId))
        .thenReturn(new RoomOwnershipService.ClaimOutcome.AlreadyMine(1L));

    authorizer.authorize(user, "/topic/room." + roomId, "stomp-session-1");
    // A retried SUBSCRIBE on the same STOMP session is not a new tab (FR-GRACE-006) — must not
    // resolve the reconnect twice.
    authorizer.authorize(user, "/topic/room." + roomId, "stomp-session-1");

    verify(roomService).onConnect(roomId, user.userId());
  }

  /** {@code IMP-GATE-51}, {@code reconnection-flow.md} FR-REJOIN-007. */
  @Test
  void aCallerWhoLostTheRaceToItsOwnGraceExpiryReceivesSlotReleased() {
    when(rooms.isCurrentParticipant(roomId, user.userId())).thenReturn(false);
    when(presence.lostRaceToOwnExpiry(roomId, user.userId())).thenReturn(true);

    SubscriptionDecision decision =
        authorizer.authorize(user, "/topic/room." + roomId, "stomp-session-1");

    assertThat(decision).isInstanceOf(SubscriptionDecision.RejectWithError.class);
    SubscriptionDecision.RejectWithError rejection =
        (SubscriptionDecision.RejectWithError) decision;
    assertThat(rejection.code()).isEqualTo("SLOT_RELEASED");
    assertThat(rejection.retryAfterMs()).isZero();
    verify(ownership, never()).claimWithRetry(any());
  }

  @Test
  void aMemberOnAPodThatDoesNotOwnTheRoomIsToldToRetry() {
    when(rooms.isCurrentParticipant(roomId, user.userId())).thenReturn(true);
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
    when(rooms.isCurrentParticipant(roomId, user.userId())).thenReturn(true);
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
    when(rooms.isCurrentParticipant(roomId, user.userId())).thenReturn(true);
    when(ownership.claimWithRetry(roomId))
        .thenReturn(new RoomOwnershipService.ClaimOutcome.AlreadyMine(1L));
    authorizer.authorize(user, "/topic/room." + roomId, "stomp-session-1");

    authorizer.onSessionDisconnect(disconnectEvent("stomp-session-1"));
    authorizer.onRoomOwnershipLost(new RoomOwnershipLostEvent(roomId, "test"));

    verify(connectionRegistry, never()).closeSession(anyString(), anyString(), anyString());
  }

  @Test
  void aGenuineSessionDisconnectFeedsTheDisconnectHandler() {
    when(rooms.isCurrentParticipant(roomId, user.userId())).thenReturn(true);
    when(ownership.claimWithRetry(roomId))
        .thenReturn(new RoomOwnershipService.ClaimOutcome.AlreadyMine(1L));
    authorizer.authorize(user, "/topic/room." + roomId, "stomp-session-1");

    authorizer.onSessionDisconnect(disconnectEvent("stomp-session-1"));

    verify(roomService).onDisconnect(roomId, user.userId());
  }

  @Test
  void anInstanceInitiatedCloseNeverReachesTheDisconnectHandler() {
    when(rooms.isCurrentParticipant(roomId, user.userId())).thenReturn(true);
    when(ownership.claimWithRetry(roomId))
        .thenReturn(new RoomOwnershipService.ClaimOutcome.AlreadyMine(1L));
    authorizer.authorize(user, "/topic/room." + roomId, "stomp-session-1");

    // room-lifecycle.md §1.6: the session was already tagged and severed before the transport
    // layer's own async SessionDisconnectEvent fires for it.
    authorizer.onRoomOwnershipLost(new RoomOwnershipLostEvent(roomId, "test"));
    authorizer.onSessionDisconnect(disconnectEvent("stomp-session-1"));

    verify(roomService, never()).onDisconnect(any(), any());
  }

  /**
   * {@code IMP-M16-13}: the bookkeeping is sized by the sessions that are connected, not by the
   * rooms this instance has served; a disconnect used to walk every room it had ever tracked.
   */
  @Test
  void aDisconnectedSessionLeavesNoRoomBehind() {
    when(rooms.isCurrentParticipant(any(), any())).thenReturn(true);
    when(ownership.claimWithRetry(any()))
        .thenReturn(new RoomOwnershipService.ClaimOutcome.AlreadyMine(1L));
    for (int i = 0; i < 50; i++) {
      authorizer.authorize(user, "/topic/room." + UUID.randomUUID(), "stomp-session-" + i);
    }
    assertThat(authorizer.trackedRooms()).isEqualTo(50);
    assertThat(authorizer.trackedSessions()).isEqualTo(50);

    for (int i = 0; i < 50; i++) {
      authorizer.onSessionDisconnect(disconnectEvent("stomp-session-" + i));
    }

    assertThat(authorizer.trackedRooms()).isZero();
    assertThat(authorizer.trackedSessions()).isZero();
  }

  @Test
  void aRoomKeepsItsOtherSessionsWhenOneDisconnects() {
    when(rooms.isCurrentParticipant(roomId, user.userId())).thenReturn(true);
    when(ownership.claimWithRetry(roomId))
        .thenReturn(new RoomOwnershipService.ClaimOutcome.AlreadyMine(1L));
    authorizer.authorize(user, "/topic/room." + roomId, "stomp-session-1");
    authorizer.authorize(user, "/topic/room." + roomId, "stomp-session-2");

    authorizer.onSessionDisconnect(disconnectEvent("stomp-session-1"));
    authorizer.onRoomOwnershipLost(new RoomOwnershipLostEvent(roomId, "test"));

    verify(connectionRegistry).closeSession(eq("stomp-session-2"), anyString(), anyString());
    verify(connectionRegistry, never())
        .closeSession(eq("stomp-session-1"), anyString(), anyString());
    assertThat(authorizer.trackedRooms()).isZero();
    assertThat(authorizer.trackedSessions()).isZero();
  }

  @Test
  void aSessionSubscribedToTwoRoomsIsForgottenInBoth() {
    UUID other = UUID.randomUUID();
    when(rooms.isCurrentParticipant(any(), any())).thenReturn(true);
    when(ownership.claimWithRetry(any()))
        .thenReturn(new RoomOwnershipService.ClaimOutcome.AlreadyMine(1L));
    authorizer.authorize(user, "/topic/room." + roomId, "stomp-session-1");
    authorizer.authorize(user, "/topic/room." + other, "stomp-session-1");

    authorizer.onSessionDisconnect(disconnectEvent("stomp-session-1"));
    authorizer.onRoomOwnershipLost(new RoomOwnershipLostEvent(roomId, "test"));
    authorizer.onRoomOwnershipLost(new RoomOwnershipLostEvent(other, "test"));

    verify(connectionRegistry, never()).closeSession(anyString(), anyString(), anyString());
    assertThat(authorizer.trackedRooms()).isZero();
    assertThat(authorizer.trackedSessions()).isZero();
  }

  private static SessionDisconnectEvent disconnectEvent(String stompSessionId) {
    return new SessionDisconnectEvent(
        new Object(),
        MessageBuilder.withPayload(new byte[0]).build(),
        stompSessionId,
        CloseStatus.NORMAL);
  }
}
