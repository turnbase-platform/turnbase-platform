package io.turnbase.platform.room;

import io.turnbase.platform.common.AuthenticatedUser;
import io.turnbase.platform.game.SessionRoster;
import io.turnbase.platform.websocket.SubscriptionAuthorizer;
import io.turnbase.platform.websocket.SubscriptionDecision;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * {@code websocket.md} § Authorization model (game), channel level: {@code /topic/game.{sessionId}}
 * admits any participant, {@code /topic/game.{sessionId}.team.{teamId}} only a player assigned to
 * that team — spectators are refused it outright (FR-FE-006). Both drop silently. This is the
 * fast-fail; what an effect is addressed to is checked at fan-out.
 */
@Component
class GameSubscriptionAuthorizer implements SubscriptionAuthorizer {

  private static final String PREFIX = "/topic/game.";

  private final RoomSessionRosterSource rosters;

  GameSubscriptionAuthorizer(RoomSessionRosterSource rosters) {
    this.rosters = rosters;
  }

  @Override
  public boolean supports(String destination) {
    return destination.startsWith(PREFIX);
  }

  @Override
  public SubscriptionDecision authorize(
      AuthenticatedUser user, String destination, String stompSessionId) {
    String[] parts = destination.substring(PREFIX.length()).split("\\.", -1);
    try {
      UUID sessionId = UUID.fromString(parts[0]);
      SessionRoster roster = rosters.rosterOf(sessionId).orElse(null);
      if (roster == null) {
        return new SubscriptionDecision.SilentlyDrop();
      }
      if (parts.length == 1) {
        boolean participant =
            roster.hasPlayer(user.userId()) || roster.spectators().contains(user.userId());
        return participant
            ? new SubscriptionDecision.Allow()
            : new SubscriptionDecision.SilentlyDrop();
      }
      if (parts.length == 3 && "team".equals(parts[1])) {
        UUID teamId = UUID.fromString(parts[2]);
        return teamId.equals(roster.playerTeams().get(user.userId()))
            ? new SubscriptionDecision.Allow()
            : new SubscriptionDecision.SilentlyDrop();
      }
    } catch (IllegalArgumentException e) {
      // fall through
    }
    return new SubscriptionDecision.SilentlyDrop();
  }
}
