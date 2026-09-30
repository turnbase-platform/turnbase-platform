package io.turnbase.platform.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.turnbase.platform.common.AuthenticatedUser;
import io.turnbase.platform.game.SessionRoster;
import io.turnbase.platform.websocket.SubscriptionDecision;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** {@code websocket.md} § Authorization model (game), channel level. */
class GameSubscriptionAuthorizerTest {

  private final RoomSessionRosterSource rosters = mock(RoomSessionRosterSource.class);
  private final GameSubscriptionAuthorizer authorizer = new GameSubscriptionAuthorizer(rosters);

  private final UUID sessionId = UUID.randomUUID();
  private final UUID teamA = UUID.randomUUID();
  private final AuthenticatedUser player =
      new AuthenticatedUser(UUID.randomUUID(), UUID.randomUUID());
  private final AuthenticatedUser spectator =
      new AuthenticatedUser(UUID.randomUUID(), UUID.randomUUID());
  private final AuthenticatedUser stranger =
      new AuthenticatedUser(UUID.randomUUID(), UUID.randomUUID());

  private SubscriptionDecision decide(AuthenticatedUser user, String destination) {
    when(rosters.rosterOf(sessionId))
        .thenReturn(
            Optional.of(
                new SessionRoster(Map.of(player.userId(), teamA), Set.of(spectator.userId()))));
    return authorizer.authorize(user, destination, "stomp-1");
  }

  @Test
  void supportsTheGameTopicsOnly() {
    assertThat(authorizer.supports("/topic/game." + sessionId)).isTrue();
    assertThat(authorizer.supports("/topic/game." + sessionId + ".team." + teamA)).isTrue();
    assertThat(authorizer.supports("/topic/room." + sessionId)).isFalse();
  }

  @Test
  void playersAndSpectatorsMaySubscribeToTheSessionTopic() {
    String topic = "/topic/game." + sessionId;
    assertThat(decide(player, topic)).isInstanceOf(SubscriptionDecision.Allow.class);
    assertThat(decide(spectator, topic)).isInstanceOf(SubscriptionDecision.Allow.class);
    assertThat(decide(stranger, topic)).isInstanceOf(SubscriptionDecision.SilentlyDrop.class);
  }

  @Test
  void onlyAPlayerOfTheTeamMaySubscribeToItsTopic() {
    String topic = "/topic/game." + sessionId + ".team." + teamA;
    assertThat(decide(player, topic)).isInstanceOf(SubscriptionDecision.Allow.class);
    assertThat(decide(spectator, topic)).isInstanceOf(SubscriptionDecision.SilentlyDrop.class);
    assertThat(decide(stranger, topic)).isInstanceOf(SubscriptionDecision.SilentlyDrop.class);
    assertThat(decide(player, "/topic/game." + sessionId + ".team." + UUID.randomUUID()))
        .isInstanceOf(SubscriptionDecision.SilentlyDrop.class);
  }

  @Test
  void anUnknownSessionOrMalformedDestinationIsDropped() {
    when(rosters.rosterOf(sessionId)).thenReturn(Optional.empty());
    assertThat(authorizer.authorize(player, "/topic/game." + sessionId, "s"))
        .isInstanceOf(SubscriptionDecision.SilentlyDrop.class);
    assertThat(authorizer.authorize(player, "/topic/game.not-a-uuid", "s"))
        .isInstanceOf(SubscriptionDecision.SilentlyDrop.class);
  }
}
