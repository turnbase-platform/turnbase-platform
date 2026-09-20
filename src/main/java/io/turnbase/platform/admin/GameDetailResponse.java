package io.turnbase.platform.admin;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/** {@code admin-rest-api.md} §5.2. */
record GameDetailResponse(
    java.util.UUID gameId,
    String title,
    String shortDescription,
    @Nullable String developerUsername,
    String badge,
    boolean listed,
    @Nullable ActiveVersionView activeVersion,
    @Nullable OpenReviewView openReview) {

  record ActiveVersionView(String version, Instant activatedAt) {}

  record OpenReviewView(Instant submittedAt) {}

  static GameDetailResponse of(AdminGameRepository.GameDetail detail) {
    return new GameDetailResponse(
        detail.gameId(),
        detail.title(),
        detail.shortDescription(),
        detail.developerUsername(),
        detail.badge(),
        detail.listed(),
        detail.activeVersion() == null
            ? null
            : new ActiveVersionView(
                detail.activeVersion().version(), detail.activeVersion().activatedAt()),
        detail.openReviewSubmittedAt() == null
            ? null
            : new OpenReviewView(detail.openReviewSubmittedAt()));
  }
}
