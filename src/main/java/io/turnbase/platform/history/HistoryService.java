package io.turnbase.platform.history;

import io.turnbase.platform.common.Canonicalizer;
import io.turnbase.platform.common.NotFoundException;
import io.turnbase.platform.common.UsernameSkeleton;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

/** {@code history-rest-api.md} §2–§4. */
@Service
class HistoryService {

  private final HistoryRepository repository;

  HistoryService(HistoryRepository repository) {
    this.repository = repository;
  }

  Page<HistoryEntryResponse> historyOf(
      String username, @Nullable UUID viewerId, @Nullable String search, Pageable pageable) {
    String skeleton = UsernameSkeleton.of(Canonicalizer.canonicalize(username));
    HistoryRepository.ProfileVisibility profile =
        repository.findProfile(skeleton).orElseThrow(() -> new NotFoundException("No such user."));
    if (!isVisible(profile, viewerId)) {
      return new PageImpl<>(List.of(), pageable, 0);
    }
    var content =
        repository
            .page(profile.userId(), search, pageable.getPageSize(), pageable.getOffset())
            .stream()
            .map(HistoryService::toResponse)
            .toList();
    return new PageImpl<>(content, pageable, repository.count(profile.userId(), search));
  }

  private static HistoryEntryResponse toResponse(HistoryRepository.Row row) {
    return new HistoryEntryResponse(
        row.gameId(),
        row.gameName(),
        row.linkable(),
        row.completedAt(),
        OutcomeLabel.of(row.draw(), row.placement(), row.teamCount()),
        row.playerStats(),
        row.teamStats());
  }

  /** FR-HIST-028 (owner exempt), FR-HIST-029, FR-PROF-006…007; guests and erased accounts: none. */
  private static boolean isVisible(
      HistoryRepository.ProfileVisibility profile, @Nullable UUID viewerId) {
    if (profile.deleted() || "GUEST".equals(profile.kind())) {
      return false;
    }
    if (viewerId != null && viewerId.equals(profile.userId())) {
      return true;
    }
    return !Boolean.TRUE.equals(profile.profilePrivate())
        && !"HIDDEN".equals(profile.historyVisibility());
  }
}
