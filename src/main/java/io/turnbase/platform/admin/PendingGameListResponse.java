package io.turnbase.platform.admin;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** {@code admin-rest-api.md} §5.1. */
record PendingGameListResponse(List<Item> items) {

  record Item(UUID gameId, String title, @Nullable String developerUsername, Instant submittedAt) {}

  static PendingGameListResponse of(List<AdminGameRepository.PendingReview> rows) {
    return new PendingGameListResponse(
        rows.stream()
            .map(
                row ->
                    new Item(row.gameId(), row.title(), row.developerUsername(), row.submittedAt()))
            .toList());
  }
}
