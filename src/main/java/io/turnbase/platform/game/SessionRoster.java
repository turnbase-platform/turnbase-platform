package io.turnbase.platform.game;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Who a session's effects may be addressed to (IR-17): its current players, each with the team they
 * play on, and its spectators. Spectators are never valid {@code send_to_player} targets — they
 * receive {@code broadcast}-originated events only (FR-FE-006).
 */
public record SessionRoster(Map<UUID, UUID> playerTeams, Set<UUID> spectators) {

  public boolean hasPlayer(UUID userId) {
    return playerTeams.containsKey(userId);
  }

  public boolean hasTeam(UUID teamId) {
    return playerTeams.containsValue(teamId);
  }
}
