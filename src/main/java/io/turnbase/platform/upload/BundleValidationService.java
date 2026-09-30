package io.turnbase.platform.upload;

import io.turnbase.bundle.BundleValidator;
import io.turnbase.bundle.Finding;
import io.turnbase.platform.common.CheckError;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Phases C-G of {@code bundle-format.md} §3, run asynchronously after {@code confirm} returns
 * {@code 202} (§3.1). §3.3's barriers: C, D are each a barrier for what follows; E1-E6 accumulate
 * with no barrier among them; a failing E1/E2 skips F entirely; F1/F2 are a barrier for F3-F7; G
 * runs only when every prior stage produced zero errors.
 */
@Service
class BundleValidationService {

  private static final Logger log = LoggerFactory.getLogger(BundleValidationService.class);

  private final GameVersionRepository repository;
  private final ArtifactStore artifactStore;
  private final PublicationService publicationService;
  private final UploadProperties uploadProperties;
  private final GdkCompatibilityProperties gdkProperties;
  private final BundleValidator bundleValidator = new BundleValidator();

  BundleValidationService(
      GameVersionRepository repository,
      ArtifactStore artifactStore,
      PublicationService publicationService,
      UploadProperties uploadProperties,
      GdkCompatibilityProperties gdkProperties) {
    this.repository = repository;
    this.artifactStore = artifactStore;
    this.publicationService = publicationService;
    this.uploadProperties = uploadProperties;
    this.gdkProperties = gdkProperties;
  }

  /**
   * {@code arrivedViaAdminListener} is E4's second condition ({@code admin-rest-api.md} §4,
   * inherited from IMP-M4-03's own session note): {@code runtime: "jvm"} is legal only for a
   * confirm that reached this pipeline through the admin listener's routes, never the public {@code
   * /api/games/{gameId}/versions/{uploadId}/confirm} ones — there is no shared session, token, or
   * header the two listeners both see, so which controller called {@link
   * VersionUploadService#confirm} is the only signal available, and it must be threaded through
   * rather than re-derived here.
   */
  @Async(UploadAsyncConfig.EXECUTOR_BEAN_NAME)
  void validateAsync(UUID gameId, UUID versionId, boolean arrivedViaAdminListener) {
    try {
      run(gameId, versionId, arrivedViaAdminListener);
    } catch (RuntimeException e) {
      log.error("Bundle validation pipeline crashed for game {} version {}", gameId, versionId, e);
      repository.markFailed(
          versionId,
          List.of(
              new CheckError(
                  "pipeline.interrupted", "The validation pipeline failed unexpectedly: " + e)),
          List.of());
    }
  }

  private void run(UUID gameId, UUID versionId, boolean arrivedViaAdminListener) {
    GameVersionRow row = repository.findByVersionId(versionId).orElseThrow();
    GameRow game = repository.findGame(gameId).orElseThrow();
    String bundleKey = row.bundleObjectKey();
    if (bundleKey == null) {
      repository.markFailed(
          versionId,
          List.of(new CheckError("pipeline.interrupted", "Missing bundle object key.")),
          List.of());
      return;
    }

    byte[] zipBytes;
    try {
      Path temp = artifactStore.downloadToTempFile(bundleKey);
      try {
        zipBytes = Files.readAllBytes(temp);
      } finally {
        Files.deleteIfExists(temp);
      }
    } catch (IOException | RuntimeException e) {
      repository.markFailed(
          versionId,
          List.of(
              new CheckError("pipeline.interrupted", "Failed to fetch bundle from storage: " + e)),
          List.of());
      return;
    }

    // Phases C-F: the shared validator (IMP-M3-13). E4's trust rule is the platform's, so it is
    // decided here and handed in.
    boolean jvmRuntimeAllowed =
        "OFFICIAL".equals(game.badge()) && game.ownerUserId() == null && arrivedViaAdminListener;
    GdkCompatibilityProperties.Range range = gdkProperties.range();
    BundleValidator.Result validation =
        bundleValidator.validate(
            zipBytes,
            new BundleValidator.Options(
                uploadProperties.maxUncompressedSize().toBytes(),
                uploadProperties.maxEntryUncompressedSize().toBytes(),
                uploadProperties.maxCompressionRatio(),
                uploadProperties.maxEntryCount(),
                uploadProperties.maxInfoDocSize().toBytes(),
                jvmRuntimeAllowed,
                range.low(),
                range.high(),
                row.version()));
    List<CheckError> warnings = toCheckErrors(validation.warnings());
    BundleValidator.Bundle bundle = validation.bundle().orElse(null);
    if (bundle == null) {
      repository.markFailed(versionId, toCheckErrors(validation.errors()), warnings);
      return;
    }

    // Phase G.
    PublicationService.Outcome outcome =
        publicationService.publishAndActivate(
            gameId,
            versionId,
            row.version(),
            bundle.manifest(),
            bundle.manifestText(),
            bundle.gdkApiVersion(),
            bundle.archive(),
            bundle.backendPath(),
            bundle.infoDoc());
    if (!outcome.activated()) {
      repository.markFailed(versionId, outcome.errors(), warnings);
    }
  }

  private static List<CheckError> toCheckErrors(List<Finding> findings) {
    return findings.stream().map(f -> new CheckError(f.code(), f.message())).toList();
  }
}
