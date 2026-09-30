package io.turnbase.platform.room;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.turnbase.grpc.gamehost.v1.ActionInput;
import io.turnbase.platform.common.AuthenticatedUser;
import io.turnbase.platform.game.GameInputService;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessagingTemplate;

class GameActionControllerTest {

  private final ObjectMapper mapper = new ObjectMapper();
  private final RoomRepository repository = mock(RoomRepository.class);
  private final GameInputService inputs = mock(GameInputService.class);
  private final SimpMessagingTemplate messaging = mock(SimpMessagingTemplate.class);
  private final GameActionController controller =
      new GameActionController(repository, inputs, messaging, mapper);

  private final UUID sessionId = UUID.randomUUID();
  private final UUID userId = UUID.randomUUID();
  private final UUID teamId = UUID.randomUUID();
  private final AuthenticatedUser principal = mock(AuthenticatedUser.class);

  private RoomCommandEnvelope action(String json) throws Exception {
    return new RoomCommandEnvelope("SUBMIT_ACTION", "c-1", mapper.readTree(json));
  }

  @SuppressWarnings("unchecked")
  private String errorCode() {
    ArgumentCaptor<Object> sent = ArgumentCaptor.forClass(Object.class);
    verify(messaging).convertAndSendToUser(any(), eq("/queue/errors"), sent.capture());
    Map<String, Object> envelope = (Map<String, Object>) sent.getValue();
    Map<String, Object> payload = (Map<String, Object>) envelope.get("payload");
    assertThat(payload).containsEntry("correlationId", "c-1");
    return String.valueOf(requireNonNull(payload).get("code"));
  }

  @Test
  void attachesTheServerSideIdentityAndIgnoresAClaimedOne() throws Exception {
    when(principal.userId()).thenReturn(userId);
    when(repository.actingParticipant(sessionId, userId))
        .thenReturn(Optional.of(new RoomRepository.ActingParticipant("PLAYER", teamId, "bob")));
    when(inputs.submitAction(eq(sessionId), any())).thenReturn(GameInputService.Outcome.DELIVERED);

    controller.handle(sessionId, action("{\"move\":\"e4\",\"playerId\":\"mallory\"}"), principal);

    ArgumentCaptor<ActionInput> sent = ArgumentCaptor.forClass(ActionInput.class);
    verify(inputs).submitAction(eq(sessionId), sent.capture());
    assertThat(sent.getValue().getPlayer().getPlayerId()).isEqualTo(userId.toString());
    assertThat(sent.getValue().getPlayer().getDisplayName()).isEqualTo("bob");
    assertThat(sent.getValue().getPlayer().getTeamId()).isEqualTo(teamId.toString());
    assertThat(sent.getValue().getPayload().toStringUtf8()).contains("\"move\":\"e4\"");
  }

  @Test
  void nonParticipantOrInactiveSessionIsNotMember() throws Exception {
    when(principal.userId()).thenReturn(userId);
    when(repository.actingParticipant(sessionId, userId)).thenReturn(Optional.empty());

    controller.handle(sessionId, action("{}"), principal);

    assertThat(errorCode()).isEqualTo("NOT_MEMBER");
    verify(inputs, never()).submitAction(any(), any());
  }

  @Test
  void spectatorIsRoleNotEligible() throws Exception {
    when(principal.userId()).thenReturn(userId);
    when(repository.actingParticipant(sessionId, userId))
        .thenReturn(Optional.of(new RoomRepository.ActingParticipant("SPECTATOR", null, "erin")));

    controller.handle(sessionId, action("{}"), principal);

    assertThat(errorCode()).isEqualTo("ROLE_NOT_ELIGIBLE");
    verify(inputs, never()).submitAction(any(), any());
  }
}
