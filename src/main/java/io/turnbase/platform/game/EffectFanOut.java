package io.turnbase.platform.game;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.ByteString;
import io.micrometer.core.instrument.MeterRegistry;
import io.turnbase.grpc.gamehost.v1.Effect;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/**
 * {@code game-host-internals.md} §10 / {@code websocket.md} § {@code GAME_EVENT}: resolves each
 * effect to a STOMP destination, in list order, and publishes it.
 *
 * <p>Game code chooses effect addresses and is untrusted, so every {@code send_to_player} and
 * {@code send_to_team} address is checked against the producing session's current membership before
 * anything is sent (IR-17, NFR-SEC-002). The per-user queue secures the <em>subscription</em>; it
 * says nothing about the <em>address</em>. A failing address drops that effect alone. Spectator
 * visibility is structural: spectators can subscribe to the session topic only, and a {@code
 * send_to_player} target must be a player, so the hidden state is never sent to them at all.
 *
 * <p>Only the first {@code game_over} of a result is delivered (FR-EFF-007). Its structured outcome
 * is never put on the wire (FR-FE-005).
 */
@Component
class EffectFanOut implements EffectDelivery {

  private static final Logger log = LoggerFactory.getLogger(EffectFanOut.class);

  private final SimpMessagingTemplate messagingTemplate;
  private final SessionRosterSource rosters;
  private final MeterRegistry meterRegistry;
  private final ObjectMapper objectMapper;

  EffectFanOut(
      SimpMessagingTemplate messagingTemplate,
      SessionRosterSource rosters,
      MeterRegistry meterRegistry,
      ObjectMapper objectMapper) {
    this.messagingTemplate = messagingTemplate;
    this.rosters = rosters;
    this.meterRegistry = meterRegistry;
    this.objectMapper = objectMapper;
  }

  @Override
  public void deliver(UUID sessionId, long sequence, List<Effect> effects) {
    if (effects.isEmpty()) {
      return;
    }
    Optional<SessionRoster> roster = rosters.rosterOf(sessionId);
    fanOut(sessionId, sequence, effects, roster.orElse(null));
  }

  @Override
  public void deliverInitial(UUID sessionId, List<Effect> effects, SessionRoster roster) {
    fanOut(sessionId, 0, effects, roster);
  }

  private void fanOut(
      UUID sessionId, long sequence, List<Effect> effects, @Nullable SessionRoster roster) {
    boolean gameOverSeen = false;
    for (int index = 0; index < effects.size(); index++) {
      Effect effect = effects.get(index);
      try {
        gameOverSeen = fanOutOne(sessionId, sequence, index, effect, roster, gameOverSeen);
      } catch (IllegalStateException e) {
        // One malformed payload is the game's defect; the effects around it are still owed.
        log.error(
            "sessionId={} sequence={} index={} effect dropped", sessionId, sequence, index, e);
      }
    }
  }

  private boolean fanOutOne(
      UUID sessionId,
      long sequence,
      int index,
      Effect effect,
      @Nullable SessionRoster roster,
      boolean gameOverSeen) {
    {
      switch (effect.getEffectCase()) {
        case BROADCAST ->
            publish(
                "/topic/game." + sessionId,
                message(
                    "GAME_EVENT", sessionId, sequence, index, effect.getBroadcast().getPayload()));
        case SEND_TO_PLAYER -> {
          var target = effect.getSendToPlayer();
          UUID playerId = parse(target.getPlayerId());
          if (playerId == null || roster == null || !roster.hasPlayer(playerId)) {
            reject("player", sessionId, target.getPlayerId());
          } else {
            Map<String, Object> message =
                message("GAME_EVENT", sessionId, sequence, index, target.getPayload());
            messagingTemplate.convertAndSendToUser(playerId.toString(), "/queue/game", message);
          }
        }
        case SEND_TO_TEAM -> {
          var target = effect.getSendToTeam();
          UUID teamId = parse(target.getTeamId());
          if (teamId == null || roster == null || !roster.hasTeam(teamId)) {
            reject("team", sessionId, target.getTeamId());
          } else {
            publish(
                "/topic/game." + sessionId + ".team." + teamId,
                message("GAME_EVENT", sessionId, sequence, index, target.getPayload()));
          }
        }
        case LOG -> {
          Map<String, Object> message = base("GAME_LOG", sessionId, sequence, index);
          message.put("payload", Map.of("entry", effect.getLog().getEntry()));
          publish("/topic/game." + sessionId, message);
        }
        case GAME_OVER -> {
          if (gameOverSeen) {
            log.warn("sessionId={} sequence={} repeated game_over ignored", sessionId, sequence);
          } else {
            gameOverSeen = true;
            Map<String, Object> message =
                resultsMessage(
                    sessionId, sequence, index, effect.getGameOver().getResultsPayload());
            publish("/topic/game." + sessionId, message);
          }
        }
        case EFFECT_NOT_SET ->
            log.warn("sessionId={} sequence={} index={} empty effect", sessionId, sequence, index);
      }
    }
    return gameOverSeen;
  }

  private void publish(String destination, Map<String, Object> message) {
    messagingTemplate.convertAndSend(destination, message);
  }

  private void reject(String kind, UUID sessionId, String address) {
    log.warn("Effect address rejected kind={} session_id={} address={}", kind, sessionId, address);
    meterRegistry.counter("turnbase.effect.address_rejected.total", "kind", kind).increment();
  }

  private static @Nullable UUID parse(String value) {
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  private Map<String, Object> base(String type, UUID sessionId, long sequence, int index) {
    Map<String, Object> message = new LinkedHashMap<>();
    message.put("type", type);
    message.put("sessionId", sessionId);
    message.put("sequence", sequence);
    message.put("index", index);
    return message;
  }

  private Map<String, Object> message(
      String type, UUID sessionId, long sequence, int index, ByteString payload) {
    Map<String, Object> message = base(type, sessionId, sequence, index);
    message.put("payload", json(payload));
    return message;
  }

  private Map<String, Object> resultsMessage(
      UUID sessionId, long sequence, int index, ByteString results) {
    Map<String, Object> message = base("GAME_OVER", sessionId, sequence, index);
    message.put("payload", Map.of("results", json(results)));
    return message;
  }

  private JsonNode json(ByteString payload) {
    if (payload.isEmpty()) {
      return objectMapper.createObjectNode();
    }
    try {
      return objectMapper.readTree(payload.toByteArray());
    } catch (java.io.IOException e) {
      throw new IllegalStateException("effect payload is not JSON", e);
    }
  }
}
