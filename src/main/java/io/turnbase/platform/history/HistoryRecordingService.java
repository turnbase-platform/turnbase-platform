package io.turnbase.platform.history;

import io.micrometer.core.instrument.MeterRegistry;
import io.turnbase.platform.game.GameOutcomeRecorder;
import io.turnbase.platform.game.GameOutcomeReport;
import io.turnbase.platform.game.SessionRoster;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code flows/06-game-end-and-rematch.md} §B–C. The outcome is game code's, so it is validated
 * before any row is written: a structurally invalid one still counts the completed session but
 * writes no entries, because {@code ghe_placement_ck} rejecting a bad placement would roll back the
 * whole record with no retry left ({@code game_over} is terminal, FR-EFF-006).
 */
@Service
class HistoryRecordingService implements GameOutcomeRecorder {

  private static final Logger log = LoggerFactory.getLogger(HistoryRecordingService.class);

  private final HistoryRepository repository;
  private final MeterRegistry meterRegistry;

  HistoryRecordingService(HistoryRepository repository, MeterRegistry meterRegistry) {
    this.repository = repository;
    this.meterRegistry = meterRegistry;
  }

  @Override
  @Transactional
  public void record(GameOutcomeReport report) {
    SessionRoster roster = report.roster();
    int playerCount = roster == null ? 0 : roster.playerTeams().size();
    boolean inserted =
        repository.insertCompletion(
            report.sessionId(),
            report.gameId(),
            report.gameVersion(),
            report.completedAt(),
            playerCount,
            report.teams().size(),
            report.draw());
    if (!inserted) {
      return; // FR-EFF-007 made durable: a re-delivered game_over writes and counts nothing.
    }
    repository.incrementCompletedSessions(report.gameId());
    if (roster == null) {
      return;
    }
    String problem = validate(report, roster);
    if (problem != null) {
      log.error(
          "Invalid game outcome, no history entries written sessionId={} gameId={} version={}: {}",
          report.sessionId(),
          report.gameId(),
          report.gameVersion(),
          problem);
      meterRegistry.counter("turnbase.history.invalid_outcome.total").increment();
      return;
    }
    writeEntries(report, roster);
  }

  private void writeEntries(GameOutcomeReport report, SessionRoster roster) {
    Map<String, GameOutcomeReport.TeamOutcome> teams = new HashMap<>();
    report.teams().forEach(team -> teams.put(team.teamId(), team));
    Map<String, Map<String, String>> playerStats = new HashMap<>();
    report.players().forEach(player -> playerStats.put(player.playerId(), player.stats()));
    String gameName =
        repository.titleOf(report.gameId()).orElseGet(() -> report.gameId().toString());
    List<UUID> registered = repository.registeredAmong(roster.playerTeams().keySet());
    for (UUID userId : registered) {
      UUID teamId = roster.playerTeams().get(userId);
      GameOutcomeReport.TeamOutcome team = teamId == null ? null : teams.get(teamId.toString());
      if (team == null) {
        continue; // unreachable once validated; a skipped entry beats an aborted record
      }
      repository.insertEntry(
          report.sessionId(),
          userId,
          report.gameId(),
          gameName,
          report.completedAt(),
          team.placement(),
          report.teams().size(),
          report.draw(),
          playerStats.getOrDefault(userId.toString(), Map.of()),
          team.stats());
    }
  }

  /** {@code null} when valid. */
  private static @Nullable String validate(GameOutcomeReport report, SessionRoster roster) {
    int teamCount = report.teams().size();
    if (teamCount == 0) {
      return "teamResults is empty";
    }
    Map<String, GameOutcomeReport.TeamOutcome> byId = new HashMap<>();
    for (GameOutcomeReport.TeamOutcome team : report.teams()) {
      if (team.placement() < 1 || team.placement() > teamCount) {
        return "placement " + team.placement() + " outside [1, " + teamCount + "]";
      }
      byId.put(team.teamId(), team);
    }
    for (GameOutcomeReport.PlayerOutcome player : report.players()) {
      boolean participant =
          roster.playerTeams().keySet().stream()
              .anyMatch(id -> id.toString().equals(player.playerId()));
      if (!participant) {
        return "playerResults names a non-participant";
      }
    }
    for (UUID teamId : roster.playerTeams().values()) {
      if (!byId.containsKey(teamId.toString())) {
        return "a playing team has no teamResults entry";
      }
    }
    return null;
  }
}
