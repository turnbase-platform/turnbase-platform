package io.turnbase.platform.game;

import io.turnbase.grpc.gamehost.v1.Effect;
import java.util.List;
import java.util.UUID;

/**
 * The seam between Orchestration's dispatch path and effect fan-out ({@code game-host-internals.md}
 * §10). Dispatch decides <em>whether</em> a result is delivered (the per-session watermark, exactly
 * once per sequence); fan-out, which is {@code IMP-M3-07}, decides to whom.
 */
public interface EffectDelivery {

  /**
   * @param sequence the input's sequence, {@code 0} for {@code CreateSession}'s initial effects
   * @param effects in the order the handler produced them; never reordered
   */
  void deliver(UUID sessionId, long sequence, List<Effect> effects);
}
