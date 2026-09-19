package io.turnbase.platform.upload;

import io.turnbase.platform.common.BusinessRuleException;
import io.turnbase.platform.common.CheckError;
import io.turnbase.platform.common.ConflictException;
import io.turnbase.platform.common.GoneException;
import io.turnbase.platform.common.NotFoundException;
import io.turnbase.platform.common.ServiceUnavailableException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Phases A and B of {@code bundle-format.md} §3 — the two synchronous stages, both resolved before
 * their HTTP request returns. Phase A ({@link #createSlot}) is {@code POST .../versions}'s own
 * request; Phase B1 ({@link #confirm}) is {@code POST .../confirm}'s, per this item's explicit
 * resolution of the tension between {@code bundle-format.md} §3.1's summary table (which groups B
 * with the async C–G job) and {@code file-uploads.md}'s flow narrative and error table (which
 * document the stored-size check and the storage-unreachable/expired-slot cases as synchronous
 * confirm-endpoint outcomes) — see the IMP-M4-03 session report and the gate opened for {@code
 * error-handling.md}'s overstated "the only one" claim.
 */
@Service
class VersionUploadService {

  private static final Logger log = LoggerFactory.getLogger(VersionUploadService.class);

  private final GameVersionRepository repository;
  private final ArtifactStore artifactStore;
  private final UploadProperties uploadProperties;
  private final WorkerIdentity workerIdentity;
  private final BundleValidationService bundleValidationService;

  VersionUploadService(
      GameVersionRepository repository,
      ArtifactStore artifactStore,
      UploadProperties uploadProperties,
      WorkerIdentity workerIdentity,
      BundleValidationService bundleValidationService) {
    this.repository = repository;
    this.artifactStore = artifactStore;
    this.uploadProperties = uploadProperties;
    this.workerIdentity = workerIdentity;
    this.bundleValidationService = bundleValidationService;
  }

  /** Phase A: stages A1-A4, {@code bundle-format.md} §3.2. */
  CreateVersionResponse createSlot(UUID gameId, CreateVersionRequest request) {
    var unused =
        repository.findGame(gameId).orElseThrow(() -> new NotFoundException("No such game."));

    List<CheckError> errors = new ArrayList<>();
    boolean validFormat = VersionOrdering.isValidFormat(request.version());
    if (!validFormat) {
      errors.add(
          new CheckError(
              "version.format",
              "Version '%s' does not match ^\\d+\\.\\d+\\.\\d+$.".formatted(request.version())));
    }
    if (request.bundleSize() > uploadProperties.maxBundleSize().toBytes()) {
      errors.add(
          new CheckError(
              "bundle.declaredSize",
              "Declared size %d exceeds the platform maximum of %d bytes."
                  .formatted(request.bundleSize(), uploadProperties.maxBundleSize().toBytes())));
    }
    if (validFormat) {
      VersionOrdering.Version candidate = VersionOrdering.Version.parse(request.version());
      repository
          .findActiveVersionOrder(gameId)
          .ifPresent(
              active -> {
                if (VersionOrdering.compare(candidate, active) <= 0) {
                  errors.add(
                      new CheckError(
                          "version.ordering",
                          "Version %s is not strictly higher than the current active version %s."
                              .formatted(candidate, active)));
                }
              });
    }
    if (!errors.isEmpty()) {
      throw new BusinessRuleException("The requested version cannot be uploaded.", errors);
    }

    Instant expiresAt = Instant.now().plus(uploadProperties.slotTtl());
    UUID versionId = allocateSlot(gameId, request, expiresAt);
    ArtifactStore.PresignedUpload presigned =
        artifactStore.presignPut(ObjectKeys.bundle(gameId, request.version()), expiresAt);
    return new CreateVersionResponse(versionId, presigned.url(), expiresAt);
  }

  /** {@code bundle-format.md} §3.2 stage A3 and §7.4's reclamation policy in one step. */
  private UUID allocateSlot(UUID gameId, CreateVersionRequest request, Instant expiresAt) {
    Optional<GameVersionRow> existing = repository.findByGameAndVersion(gameId, request.version());
    if (existing.isEmpty()) {
      return repository.insertPendingSlot(
          gameId, request.version(), request.bundleSize(), expiresAt);
    }

    GameVersionRow row = existing.get();
    boolean pendingUnexpired =
        row.status() == GameVersionStatus.PENDING && row.uploadExpiresAt().isAfter(Instant.now());
    boolean blocking =
        pendingUnexpired
            || row.status() == GameVersionStatus.PROCESSING
            || row.status() == GameVersionStatus.ACTIVE
            || row.status() == GameVersionStatus.SUPERSEDED
            || row.status() == GameVersionStatus.DISCARDED;
    if (blocking) {
      throw new ConflictException(
          "A slot for version '%s' already exists in status %s."
              .formatted(request.version(), row.status()));
    }

    // FAILED, EXPIRED, or an unexpired-window-passed PENDING row the sweep has not yet caught —
    // all three reclaim per §7.4.
    if (row.bundleObjectKey() != null) {
      try {
        artifactStore.delete(row.bundleObjectKey());
      } catch (RuntimeException e) {
        log.warn(
            "Best-effort purge of prior bundle object failed for reclaimed slot {}; the sweep"
                + " will retry.",
            row.versionId(),
            e);
      }
    }
    repository.reclaimSlot(row.versionId(), request.bundleSize(), expiresAt);
    return row.versionId();
  }

  /**
   * Phase B: {@code POST .../confirm}. Stage B1 is checked synchronously; on success the row moves
   * {@code PENDING} → {@code PROCESSING} and Phases C-G are handed to {@link
   * BundleValidationService} asynchronously.
   */
  void confirm(UUID gameId, UUID uploadId) {
    GameVersionRow row = findOwnedRow(gameId, uploadId);

    if (row.status() == GameVersionStatus.EXPIRED
        || (row.status() == GameVersionStatus.PENDING
            && !row.uploadExpiresAt().isAfter(Instant.now()))) {
      if (row.status() == GameVersionStatus.PENDING) {
        repository.markExpired(row.versionId());
      }
      throw new GoneException("The upload window for this version has closed.");
    }
    if (row.status() != GameVersionStatus.PENDING) {
      throw new ConflictException("This upload has already been confirmed.");
    }

    String bundleKey = row.bundleObjectKey();
    if (bundleKey == null) {
      throw new IllegalStateException("PENDING game_version row has no bundle_object_key");
    }

    Optional<Long> storedSize;
    try {
      storedSize = artifactStore.sizeOf(bundleKey);
    } catch (RuntimeException e) {
      throw new ServiceUnavailableException("Object storage is unreachable; retry the request.");
    }
    if (storedSize.isEmpty() || storedSize.get() > row.declaredSizeBytes()) {
      throw new BusinessRuleException(
          "The uploaded bundle does not match what was declared.",
          List.of(
              new CheckError(
                  "bundle.storedSize",
                  storedSize.isEmpty()
                      ? "No object was found at the pre-signed URL's key. Upload the bundle before"
                          + " confirming."
                      : "Stored size %d exceeds the declared size %d."
                          .formatted(storedSize.get(), row.declaredSizeBytes()))));
    }
    repository.updateStoredSize(row.versionId(), storedSize.get());

    Instant claimedUntil = Instant.now().plus(uploadProperties.validationLease());
    boolean claimed =
        repository.claimForProcessing(row.versionId(), workerIdentity.id(), claimedUntil);
    if (!claimed) {
      // Lost a race with the sweep or a second confirm between the read above and here.
      throw new ConflictException("This upload has already been confirmed.");
    }

    bundleValidationService.validateAsync(row.gameId(), row.versionId());
  }

  VersionResource get(UUID gameId, UUID uploadId) {
    return VersionResource.of(findOwnedRow(gameId, uploadId));
  }

  private GameVersionRow findOwnedRow(UUID gameId, UUID uploadId) {
    GameVersionRow row =
        repository
            .findByVersionId(uploadId)
            .orElseThrow(() -> new NotFoundException("No such upload."));
    if (!row.gameId().equals(gameId)) {
      throw new NotFoundException("No such upload.");
    }
    return row;
  }
}
