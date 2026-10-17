package io.turnbase.platform.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.turnbase.platform.common.AuthenticatedUser;
import io.turnbase.platform.websocket.SubscriptionDecision;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** {@code room-websocket.md} § Authorization Model, {@code /topic/room.{roomId}.start} row. */
class RoomStartStatusAuthorizerTest {

  private final RoomRepository repository = mock(RoomRepository.class);
  private final RoomStartStatusAuthorizer authorizer = new RoomStartStatusAuthorizer(repository);
  private final AuthenticatedUser user =
      new AuthenticatedUser(UUID.randomUUID(), UUID.randomUUID());
  private final UUID roomId = UUID.randomUUID();

  @Test
  void supportsOnlyTheStartTopic() {
    assertThat(authorizer.supports("/topic/room." + roomId + ".start")).isTrue();
    assertThat(authorizer.supports("/topic/room." + roomId)).isFalse();
    assertThat(authorizer.supports("/topic/room." + roomId + ".chat")).isFalse();
  }

  @Test
  void allowsACurrentParticipant() {
    when(repository.isCurrentParticipant(roomId, user.userId())).thenReturn(true);

    assertThat(authorizer.authorize(user, "/topic/room." + roomId + ".start", "s1"))
        .isInstanceOf(SubscriptionDecision.Allow.class);
  }

  @Test
  void silentlyDropsAnyoneElse() {
    when(repository.isCurrentParticipant(roomId, user.userId())).thenReturn(false);

    assertThat(authorizer.authorize(user, "/topic/room." + roomId + ".start", "s1"))
        .isInstanceOf(SubscriptionDecision.SilentlyDrop.class);
  }

  @Test
  void silentlyDropsAMalformedRoomId() {
    assertThat(authorizer.authorize(user, "/topic/room.not-a-uuid.start", "s1"))
        .isInstanceOf(SubscriptionDecision.SilentlyDrop.class);
  }
}
