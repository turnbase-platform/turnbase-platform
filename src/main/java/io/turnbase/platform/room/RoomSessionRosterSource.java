package io.turnbase.platform.room;

import io.turnbase.platform.game.SessionRoster;
import io.turnbase.platform.game.SessionRosterSource;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Fan-out's view of session membership, read from the room's durable participant rows. */
@Component
class RoomSessionRosterSource implements SessionRosterSource {

  private final RoomRepository repository;

  RoomSessionRosterSource(RoomRepository repository) {
    this.repository = repository;
  }

  @Override
  public Optional<SessionRoster> rosterOf(UUID sessionId) {
    List<RoomRepository.SessionParticipant> rows = repository.sessionParticipants(sessionId);
    if (rows.isEmpty()) {
      return Optional.empty();
    }
    Map<UUID, UUID> players = new HashMap<>();
    Set<UUID> spectators = new HashSet<>();
    for (RoomRepository.SessionParticipant row : rows) {
      if ("PLAYER".equals(row.role()) && row.teamId() != null) {
        players.put(row.userId(), row.teamId());
      } else if ("SPECTATOR".equals(row.role())) {
        spectators.add(row.userId());
      }
    }
    return Optional.of(new SessionRoster(players, spectators));
  }
}
