package io.turnbase.platform.room;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.turnbase.grpc.gamehost.v1.PlayerRef;
import io.turnbase.platform.common.AuthenticatedUser;
import io.turnbase.platform.game.GameLogStore;
import io.turnbase.platform.game.GameSessionService;
import io.turnbase.platform.game.GameSyncService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import tools.jackson.databind.json.JsonMapper;

class GameSyncControllerTest {

  private final JsonMapper mapper = JsonMapper.builder().build();
  private final RoomRepository repository = mock(RoomRepository.class);
  private final GameSyncService sync = mock(GameSyncService.class);
  private final SimpMessagingTemplate messaging = mock(SimpMessagingTemplate.class);
  private final GameSyncController controller = new GameSyncController(repository, sync, messaging);

  private final UUID sessionId = UUID.randomUUID();
  private final UUID roomId = UUID.randomUUID();
  private final UUID userId = UUID.randomUUID();
  private final UUID teamId = UUID.randomUUID();
  private final AuthenticatedUser principal = mock(AuthenticatedUser.class);
  private final RoomCommandEnvelope envelope =
      new RoomCommandEnvelope("REQUEST_GAME_SYNC", "c-1", null);

  @SuppressWarnings("unchecked")
  private Map<String, Object> sent(String destination) {
    ArgumentCaptor<Object> sent = ArgumentCaptor.forClass(Object.class);
    verify(messaging).convertAndSendToUser(any(), eq(destination), sent.capture());
    return (Map<String, Object>) sent.getValue();
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> child(Map<String, Object> parent, String key) {
    return (Map<String, Object>) requireNonNull(parent.get(key));
  }

  private void member(String role, @Nullable UUID team) {
    when(principal.userId()).thenReturn(userId);
    when(repository.actingParticipant(sessionId, userId))
        .thenReturn(Optional.of(new RoomRepository.ActingParticipant(role, team, "bob")));
    when(repository.inProgressRoomOf(sessionId)).thenReturn(Optional.of(roomId));
    when(repository.sessionCommand(roomId, sessionId))
        .thenReturn(
            new GameSessionService.CreateSessionCommand(
                sessionId,
                UUID.randomUUID(),
                "1.0.0",
                "x.Y",
                List.of(
                    new GameSessionService.TeamSpec(
                        teamId,
                        "Player 1",
                        List.of(new GameSessionService.PlayerSpec(userId, "bob")))),
                "{}"));
  }

  @Test
  void playerGetsInitViewAndLogInOneUnicast() {
    member("PLAYER", teamId);
    when(sync.sync(eq(sessionId), any()))
        .thenReturn(
            new GameSyncService.Snapshot(
                42,
                mapper.createObjectNode().put("yourMark", "X"),
                List.of(new GameLogStore.Entry(3, 0, "hi"))));

    controller.handle(sessionId, envelope, principal);

    ArgumentCaptor<PlayerRef> viewer = ArgumentCaptor.forClass(PlayerRef.class);
    verify(sync).sync(eq(sessionId), viewer.capture());
    assertThat(viewer.getValue().getPlayerId()).isEqualTo(userId.toString());
    Map<String, Object> message = sent("/queue/game");
    assertThat(message)
        .containsEntry("type", "GAME_SYNC")
        .containsEntry("asOfSequence", 42L)
        .containsEntry("correlationId", "c-1");
    Map<String, Object> payload = child(message, "payload");
    Map<String, Object> init = child(payload, "init");
    assertThat(init).containsEntry("role", "player");
    assertThat(child(init, "self")).containsEntry("displayName", "bob");
    assertThat((List<?>) init.get("roster")).hasSize(1);
    assertThat(requireNonNull(payload.get("view")).toString()).contains("yourMark");
    assertThat((List<?>) payload.get("log")).hasSize(1);
  }

  @Test
  void spectatorIsSyncedWithNoViewerAndNullSelf() {
    member("SPECTATOR", null);
    when(sync.sync(eq(sessionId), isNull()))
        .thenReturn(new GameSyncService.Snapshot(0, mapper.createObjectNode(), List.of()));

    controller.handle(sessionId, envelope, principal);

    Map<String, Object> payload = child(sent("/queue/game"), "payload");
    Map<String, Object> init = child(payload, "init");
    assertThat(init).containsEntry("role", "spectator").containsEntry("self", null);
  }

  @Test
  void nonMemberIsToldNotMemberAndNothingIsDispatched() {
    when(principal.userId()).thenReturn(userId);
    when(repository.actingParticipant(sessionId, userId)).thenReturn(Optional.empty());

    controller.handle(sessionId, envelope, principal);

    Map<String, Object> payload = child(sent("/queue/errors"), "payload");
    assertThat(payload).containsEntry("code", "NOT_MEMBER");
    verify(sync, never()).sync(any(), any());
  }

  @Test
  void failedResyncFailsOnlyThisClient() {
    member("PLAYER", teamId);
    when(sync.sync(eq(sessionId), any()))
        .thenThrow(new GameSyncService.ResyncFailedException("boom"));

    controller.handle(sessionId, envelope, principal);

    Map<String, Object> payload = child(sent("/queue/errors"), "payload");
    assertThat(payload).containsEntry("code", "RESYNC_FAILED");
  }
}
