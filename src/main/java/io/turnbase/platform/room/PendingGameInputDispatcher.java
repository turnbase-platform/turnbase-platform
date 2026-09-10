package io.turnbase.platform.room;

import org.springframework.stereotype.Component;

/**
 * The only {@link GameInputDispatcher} until a Game Host gRPC client exists. Always reports failure
 * — honest, since no such client exists to actually deliver anything — leaving every row for the
 * next drain tick, matching {@code game_input_outbox}'s own bounded-retry-then-dead-letter design
 * rather than pretending delivery succeeded.
 */
@Component
class PendingGameInputDispatcher implements GameInputDispatcher {

  @Override
  public boolean dispatch(long outboxRowId, long sequence, String payloadJson) {
    return false;
  }
}
