package io.turnbase.platform.upload;

import io.turnbase.platform.common.CheckError;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * {@code GET /api/games/{gameId}/versions/{uploadId}} — always {@code 200}, per {@code
 * bundle-format.md} §3.4: the version's state is the state of a resource, never a Problem Detail,
 * whatever the {@code status} value.
 */
record VersionResource(
    UUID uploadId,
    String version,
    GameVersionStatus status,
    Instant createdAt,
    List<CheckError> errors,
    List<CheckError> warnings) {

  static VersionResource of(GameVersionRow row) {
    return new VersionResource(
        row.versionId(),
        row.version(),
        row.status(),
        row.createdAt(),
        row.validationErrors(),
        row.validationWarnings());
  }
}
