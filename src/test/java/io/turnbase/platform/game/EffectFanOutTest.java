package io.turnbase.platform.game;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.ByteString;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.turnbase.grpc.gamehost.v1.BroadcastEffect;
import io.turnbase.grpc.gamehost.v1.Effect;
import io.turnbase.grpc.gamehost.v1.GameOverEffect;
import io.turnbase.grpc.gamehost.v1.LogEffect;
import io.turnbase.grpc.gamehost.v1.SendToPlayerEffect;
import io.turnbase.grpc.gamehost.v1.SendToTeamEffect;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.messaging.simp.SimpMessagingTemplate;

/** {@code websocket.md} § {@code GAME_EVENT}: every effect address is validated at fan-out. */
class EffectFanOutTest {

  private final SimpMessagingTemplate template = mock(SimpMessagingTemplate.class);
  private final SessionRosterSource rosters = mock(SessionRosterSource.class);
  private final GameLogStore logStore = mock(GameLogStore.class);
  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
  private final EffectFanOut fanOut =
      new EffectFanOut(template, rosters, meters, new ObjectMapper(), logStore);

  private final UUID sessionId = UUID.randomUUID();
  private final UUID alice = UUID.randomUUID();
  private final UUID bob = UUID.randomUUID();
  private final UUID spectator = UUID.randomUUID();
  private final UUID teamA = UUID.randomUUID();
  private final UUID teamB = UUID.randomUUID();

  private void roster() {
    when(rosters.rosterOf(sessionId))
        .thenReturn(
            Optional.of(new SessionRoster(Map.of(alice, teamA, bob, teamB), Set.of(spectator))));
  }

  private static ByteString json(String s) {
    return ByteString.copyFromUtf8(s);
  }

  private static Effect broadcast(String payload) {
    return Effect.newBuilder()
        .setBroadcast(BroadcastEffect.newBuilder().setPayload(json(payload)))
        .build();
  }

  private static Effect toPlayer(UUID id, String payload) {
    return Effect.newBuilder()
        .setSendToPlayer(
            SendToPlayerEffect.newBuilder().setPlayerId(id.toString()).setPayload(json(payload)))
        .build();
  }

  private static Effect toTeam(UUID id, String payload) {
    return Effect.newBuilder()
        .setSendToTeam(
            SendToTeamEffect.newBuilder().setTeamId(id.toString()).setPayload(json(payload)))
        .build();
  }

  @Test
  void anEffectAddressedToSomeoneOutsideTheSessionIsDroppedAndCounted() {
    roster();
    UUID stranger = UUID.randomUUID();

    fanOut.deliver(sessionId, 7, List.of(toPlayer(stranger, "{}")));

    verify(template, never()).convertAndSendToUser(anyString(), anyString(), any(Object.class));
    assertThat(meters.counter("turnbase.effect.address_rejected.total", "kind", "player").count())
        .isEqualTo(1.0);
  }

  @Test
  void aSpectatorIsNotAValidSendToPlayerTarget() {
    roster();

    fanOut.deliver(sessionId, 7, List.of(toPlayer(spectator, "{\"hand\":[1]}")));

    verify(template, never()).convertAndSendToUser(anyString(), anyString(), any(Object.class));
  }

  @Test
  void aTeamThatIsNotOfThisSessionIsDroppedAndCounted() {
    roster();

    fanOut.deliver(sessionId, 7, List.of(toTeam(UUID.randomUUID(), "{}")));

    verify(template, never()).convertAndSend(anyString(), any(Object.class));
    assertThat(meters.counter("turnbase.effect.address_rejected.total", "kind", "team").count())
        .isEqualTo(1.0);
  }

  @Test
  void everyAddressIsRejectedWhenNoRoomClaimsTheSession() {
    when(rosters.rosterOf(sessionId)).thenReturn(Optional.empty());

    fanOut.deliver(sessionId, 7, List.of(toPlayer(alice, "{}"), toTeam(teamA, "{}")));

    verify(template, never()).convertAndSendToUser(anyString(), anyString(), any(Object.class));
    verify(template, never()).convertAndSend(anyString(), any(Object.class));
  }

  @Test
  void validAddressesAreRoutedInListOrderWithTheirIndex() {
    roster();

    fanOut.deliver(
        sessionId,
        42,
        List.of(
            broadcast("{\"a\":1}"),
            toPlayer(alice, "{\"hand\":[]}"),
            toTeam(teamB, "{\"t\":1}"),
            Effect.newBuilder()
                .setLog(LogEffect.newBuilder().setEntry("**Alice** moved"))
                .build()));

    InOrder order = inOrder(template);
    ArgumentCaptor<Object> message = ArgumentCaptor.forClass(Object.class);
    order.verify(template).convertAndSend(eq("/topic/game." + sessionId), message.capture());
    order
        .verify(template)
        .convertAndSendToUser(eq(alice.toString()), eq("/queue/game"), any(Object.class));
    order
        .verify(template)
        .convertAndSend(eq("/topic/game." + sessionId + ".team." + teamB), any(Object.class));
    ArgumentCaptor<Object> log = ArgumentCaptor.forClass(Object.class);
    order.verify(template).convertAndSend(eq("/topic/game." + sessionId), log.capture());

    Map<?, ?> first = (Map<?, ?>) message.getAllValues().get(0);
    assertThat(first.get("type")).isEqualTo("GAME_EVENT");
    assertThat(first.get("sequence")).isEqualTo(42L);
    assertThat(first.get("index")).isEqualTo(0);
    Map<?, ?> logMessage = (Map<?, ?>) log.getValue();
    assertThat(logMessage.get("type")).isEqualTo("GAME_LOG");
    assertThat(logMessage.get("index")).isEqualTo(3);
  }

  @Test
  void gameOverWithheldItsStructuredOutcomeAndOnlyTheFirstIsDelivered() {
    roster();
    Effect over =
        Effect.newBuilder()
            .setGameOver(GameOverEffect.newBuilder().setResultsPayload(json("{\"winner\":\"a\"}")))
            .build();

    fanOut.deliver(sessionId, 9, List.of(over, over));

    ArgumentCaptor<Object> message = ArgumentCaptor.forClass(Object.class);
    verify(template).convertAndSend(eq("/topic/game." + sessionId), message.capture());
    Map<?, ?> sent = (Map<?, ?>) message.getValue();
    assertThat(sent.get("type")).isEqualTo("GAME_OVER");
    assertThat(sent.toString()).doesNotContain("outcome");
  }

  @Test
  void initialEffectsAreCheckedAgainstTheRosterTheSessionWasCreatedWith() {
    SessionRoster created = new SessionRoster(Map.of(alice, teamA), Set.of());

    fanOut.deliverInitial(sessionId, List.of(toPlayer(alice, "{}"), toPlayer(bob, "{}")), created);

    verify(template)
        .convertAndSendToUser(eq(alice.toString()), eq("/queue/game"), any(Object.class));
    verify(template, never())
        .convertAndSendToUser(eq(bob.toString()), anyString(), any(Object.class));
  }

  @Test
  void aMalformedPayloadDropsThatEffectOnly() {
    roster();

    fanOut.deliver(sessionId, 3, List.of(broadcast("not json"), broadcast("{\"ok\":true}")));

    verify(template, org.mockito.Mockito.times(1))
        .convertAndSend(eq("/topic/game." + sessionId), any(Object.class));
  }

  @Test
  void retainsEachLogEntryWithItsPosition() {
    roster();
    fanOut.deliver(
        sessionId,
        7,
        List.of(
            broadcast("{}"),
            Effect.newBuilder().setLog(LogEffect.newBuilder().setEntry("**X** played")).build()));

    verify(logStore).append(sessionId, new GameLogStore.Entry(7, 1, "**X** played"));
  }
}
