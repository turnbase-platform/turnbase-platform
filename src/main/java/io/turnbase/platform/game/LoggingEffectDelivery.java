package io.turnbase.platform.game;

import io.turnbase.grpc.gamehost.v1.Effect;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Stands in until {@code IMP-M3-07}'s fan-out exists — which replaces this class — and delivers to
 * no client.
 */
@Component
class LoggingEffectDelivery implements EffectDelivery {

  private static final Logger log = LoggerFactory.getLogger(LoggingEffectDelivery.class);

  @Override
  public void deliver(UUID sessionId, long sequence, List<Effect> effects) {
    log.debug(
        "No effect fan-out yet; {} effects of sessionId={} sequence={} not delivered",
        effects.size(),
        sessionId,
        sequence);
  }
}
