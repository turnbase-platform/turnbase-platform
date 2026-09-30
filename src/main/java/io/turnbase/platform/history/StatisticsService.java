package io.turnbase.platform.history;

import io.turnbase.platform.common.Canonicalizer;
import io.turnbase.platform.common.NotFoundException;
import io.turnbase.platform.common.UsernameSkeleton;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/** {@code statistics-rest-api.md} §2 — live queries, privacy-resolved like the history tab. */
@Service
class StatisticsService {

  private final HistoryRepository repository;

  StatisticsService(HistoryRepository repository) {
    this.repository = repository;
  }

  StatisticsResponse statisticsOf(String username, @Nullable UUID viewerId) {
    String skeleton = UsernameSkeleton.of(Canonicalizer.canonicalize(username));
    HistoryRepository.ProfileVisibility profile =
        repository.findProfile(skeleton).orElseThrow(() -> new NotFoundException("No such user."));
    if (!isVisible(profile, viewerId)) {
      return StatisticsResponse.zeroState();
    }
    var mostPlayed =
        repository
            .mostPlayed(profile.userId())
            .map(m -> new StatisticsResponse.MostPlayedGame(m.gameId(), m.title()))
            .orElse(null);
    return new StatisticsResponse(repository.totalPlayed(profile.userId()), mostPlayed);
  }

  /** FR-STATS-026/027 (owner exempt); guests and erased accounts answer the zero state. */
  private static boolean isVisible(
      HistoryRepository.ProfileVisibility profile, @Nullable UUID viewerId) {
    if (profile.deleted() || "GUEST".equals(profile.kind())) {
      return false;
    }
    if (viewerId != null && viewerId.equals(profile.userId())) {
      return true;
    }
    return !Boolean.TRUE.equals(profile.profilePrivate())
        && !"HIDDEN".equals(profile.statsVisibility());
  }
}
