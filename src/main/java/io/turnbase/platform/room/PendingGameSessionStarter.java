package io.turnbase.platform.room;

import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The only {@link GameSessionStarter} until {@code IMP-M3-05} lands. A no-op is the honest
 * behaviour: there is no orchestration client to call yet, and broadcasting a fabricated {@code
 * GAME_STARTED} would violate {@code room-websocket.md}'s wire contract, which requires a real
 * {@code sessionId}. {@code START_GAME}'s own preconditions are fully enforced regardless — this
 * component only stands in for what happens after they pass.
 */
@Component
class PendingGameSessionStarter implements GameSessionStarter {

  @Override
  public void start(UUID roomId, UUID hostUserId) {
    // IMP-M3-05 replaces this with a real orchestration call.
  }
}
