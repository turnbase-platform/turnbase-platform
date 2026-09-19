package io.turnbase.platform.upload;

import io.turnbase.platform.common.CheckError;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One {@code game_version} row — {@code data-model-catalog.md} §2, read in full for the {@code GET
 * .../versions/{uploadId}} resource and the validation pipeline's own use.
 */
record GameVersionRow(
    UUID versionId,
    UUID gameId,
    String version,
    GameVersionStatus status,
    @Nullable String manifestJson,
    @Nullable String gdkApiVersion,
    @Nullable String bundleObjectKey,
    long declaredSizeBytes,
    @Nullable Long storedSizeBytes,
    Instant uploadExpiresAt,
    List<CheckError> validationErrors,
    List<CheckError> validationWarnings,
    @Nullable String claimedBy,
    @Nullable Instant claimedUntil,
    Instant createdAt) {}
