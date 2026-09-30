package io.turnbase.platform.room;

import io.turnbase.platform.game.GameEndListener;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The normal-game-end caller of {@code detachSession} ({@code flows/06-game-end-and-rematch.md} §D,
 * {@code IMP-GATE-53}): the room returns to Lobby with no {@code closed_reason}. A session no room
 * claims any more was already detached, which is the idempotent no-op.
 */
@Component
class RoomGameEndListener implements GameEndListener {

  private final RoomRepository repository;
  private final RoomSessionDetacher detacher;

  RoomGameEndListener(RoomRepository repository, RoomSessionDetacher detacher) {
    this.repository = repository;
    this.detacher = detacher;
  }

  @Override
  public void onGameOver(UUID sessionId) {
    repository
        .roomOfActiveSession(sessionId)
        .ifPresent(roomId -> detacher.detach(roomId, sessionId, "GAME_OVER", "LOBBY"));
  }
}
