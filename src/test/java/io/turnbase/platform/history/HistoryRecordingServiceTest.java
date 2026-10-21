package io.turnbase.platform.history;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.turnbase.platform.game.GameOutcomeReport;
import io.turnbase.platform.game.SessionRoster;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/**
 * {@code data-model-history.md} §3: the counter's one statement runs last in the recording
 * transaction, so the lock on the game row every completion of that game updates is held for the
 * commit only ({@code IMP-M16-13}).
 */
class HistoryRecordingServiceTest {

  private final HistoryRepository repository = mock(HistoryRepository.class);
  private final HistoryRecordingService service =
      new HistoryRecordingService(repository, new SimpleMeterRegistry());

  private final UUID sessionId = UUID.randomUUID();
  private final UUID gameId = UUID.randomUUID();
  private final UUID userId = UUID.randomUUID();
  private final UUID teamId = UUID.randomUUID();

  private GameOutcomeReport report(@Nullable SessionRoster roster, int placement) {
    return new GameOutcomeReport(
        sessionId,
        gameId,
        "1.0.0",
        Instant.now(),
        false,
        List.of(new GameOutcomeReport.TeamOutcome(teamId.toString(), placement, Map.of())),
        List.of(),
        roster);
  }

  private void completionInserted(boolean inserted) {
    when(repository.insertCompletion(any(), any(), any(), any(), anyInt(), anyInt(), anyBoolean()))
        .thenReturn(inserted);
  }

  @Test
  void theCounterIsIncrementedAfterTheHistoryEntries() {
    completionInserted(true);
    when(repository.titleOf(gameId)).thenReturn(Optional.of("Tic-Tac-Toe"));
    when(repository.registeredAmong(any())).thenReturn(List.of(userId));
    SessionRoster roster = new SessionRoster(Map.of(userId, teamId), Set.of());

    service.record(report(roster, 1));

    InOrder order = inOrder(repository);
    order
        .verify(repository)
        .insertEntry(
            any(), any(), any(), any(), any(), anyInt(), anyInt(), anyBoolean(), any(), any());
    order.verify(repository).incrementCompletedSessions(gameId);
    order.verifyNoMoreInteractions();
  }

  @Test
  void theCounterIsIncrementedAfterTheLookupsWhenTheOutcomeIsInvalid() {
    completionInserted(true);
    SessionRoster roster = new SessionRoster(Map.of(userId, teamId), Set.of());

    service.record(report(roster, 5)); // placement 5 of 1 team

    InOrder order = inOrder(repository);
    order
        .verify(repository)
        .insertCompletion(any(), any(), any(), any(), anyInt(), anyInt(), anyBoolean());
    order.verify(repository).incrementCompletedSessions(gameId);
    order.verifyNoMoreInteractions();
  }

  @Test
  void aSessionWithoutARosterStillCountsAsCompleted() {
    completionInserted(true);

    service.record(report(null, 1));

    verify(repository).incrementCompletedSessions(gameId);
  }

  @Test
  void aRedeliveredGameOverCountsNothing() {
    completionInserted(false);

    service.record(report(new SessionRoster(Map.of(userId, teamId), Set.of()), 1));

    verify(repository, never()).incrementCompletedSessions(any());
  }
}
