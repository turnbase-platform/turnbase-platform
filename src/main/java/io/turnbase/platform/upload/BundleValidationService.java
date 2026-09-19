package io.turnbase.platform.upload;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.turnbase.platform.common.CheckError;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.objectweb.asm.tree.ClassNode;
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
  private static final int MAX_MANIFEST_BYTES =
      256 * 1024; // bundle-format.md §2.3 — fixed, not configurable

  private final GameVersionRepository repository;
  private final ArtifactStore artifactStore;
  private final ArchiveReader archiveReader;
  private final ManifestValidator manifestValidator;
  private final BackendJarAnalyzer backendJarAnalyzer;
  private final StateSerializabilityAnalyzer stateSerializabilityAnalyzer;
  private final PublicationService publicationService;
  private final UploadProperties uploadProperties;
  private final GdkCompatibilityProperties gdkProperties;
  private final ObjectMapper objectMapper;

  BundleValidationService(
      GameVersionRepository repository,
      ArtifactStore artifactStore,
      ArchiveReader archiveReader,
      ManifestValidator manifestValidator,
      BackendJarAnalyzer backendJarAnalyzer,
      StateSerializabilityAnalyzer stateSerializabilityAnalyzer,
      PublicationService publicationService,
      UploadProperties uploadProperties,
      GdkCompatibilityProperties gdkProperties,
      ObjectMapper objectMapper) {
    this.repository = repository;
    this.artifactStore = artifactStore;
    this.archiveReader = archiveReader;
    this.manifestValidator = manifestValidator;
    this.backendJarAnalyzer = backendJarAnalyzer;
    this.stateSerializabilityAnalyzer = stateSerializabilityAnalyzer;
    this.publicationService = publicationService;
    this.uploadProperties = uploadProperties;
    this.gdkProperties = gdkProperties;
    this.objectMapper = objectMapper;
  }

  @Async(UploadAsyncConfig.EXECUTOR_BEAN_NAME)
  void validateAsync(UUID gameId, UUID versionId) {
    try {
      run(gameId, versionId);
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

  private void run(UUID gameId, UUID versionId) {
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

    ArchiveReader.Limits limits =
        new ArchiveReader.Limits(
            uploadProperties.maxUncompressedSize().toBytes(),
            uploadProperties.maxEntryUncompressedSize().toBytes(),
            uploadProperties.maxCompressionRatio(),
            uploadProperties.maxEntryCount());

    // Phase C — barrier.
    ArchiveReadResult archive = archiveReader.read(zipBytes, limits);
    if (!archive.ok()) {
      repository.markFailed(versionId, archive.errors(), List.of());
      return;
    }

    // Phase D — barrier.
    List<CheckError> dErrors = new ArrayList<>();
    List<CheckError> warnings = new ArrayList<>();
    Optional<ArchiveEntry> manifestEntry = archive.find("manifest.json");
    if (manifestEntry.isEmpty()) {
      repository.markFailed(
          versionId,
          List.of(
              new CheckError(
                  "manifest.present", "manifest.json is missing from the archive root.")),
          List.of());
      return;
    }
    byte[] manifestBytes = manifestEntry.get().data();
    if (manifestBytes.length > MAX_MANIFEST_BYTES) {
      repository.markFailed(
          versionId,
          List.of(
              new CheckError(
                  "manifest.present", "manifest.json exceeds " + MAX_MANIFEST_BYTES + " bytes.")),
          List.of());
      return;
    }
    String manifestText;
    try {
      manifestText = decodeStrictUtf8(manifestBytes);
    } catch (CharacterCodingException e) {
      repository.markFailed(
          versionId,
          List.of(new CheckError("manifest.present", "manifest.json is not valid UTF-8.")),
          List.of());
      return;
    }
    JsonNode manifestNode;
    try {
      manifestNode = objectMapper.readTree(manifestText);
    } catch (IOException e) {
      repository.markFailed(
          versionId,
          List.of(
              new CheckError(
                  "manifest.present", "manifest.json does not parse as JSON: " + e.getMessage())),
          List.of());
      return;
    }

    ManifestValidationResult d2 = manifestValidator.validate(manifestNode);
    dErrors.addAll(d2.errors());
    warnings.addAll(d2.warnings());

    String manifestVersion = manifestNode.path("version").asText(null);
    if (manifestVersion != null && !manifestVersion.equals(row.version())) {
      dErrors.add(
          new CheckError(
              "manifest.version",
              "Manifest version '%s' does not match the declared slot version '%s'."
                  .formatted(manifestVersion, row.version())));
    }
    if (!dErrors.isEmpty()) {
      repository.markFailed(versionId, dErrors, warnings);
      return;
    }

    // Phase E — accumulate, no internal barriers.
    List<CheckError> errors = new ArrayList<>();
    String backendPath = manifestNode.path("backend").path("path").asText(null);
    String backendEntryPointClassName =
        manifestNode.path("backend").path("entryPoint").asText(null);
    String frontendEntryPoint = manifestNode.path("frontend").path("entryPoint").asText(null);
    String infoDoc =
        manifestNode.hasNonNull("infoDoc") ? manifestNode.path("infoDoc").asText(null) : null;
    String runtime = manifestNode.path("runtime").asText(null);
    String gdkApiVersion = manifestNode.path("gdkApiVersion").asText(null);

    boolean e1ok = backendPath != null && archive.find(backendPath).isPresent();
    if (!e1ok) {
      errors.add(
          new CheckError(
              "bundle.paths.backend", "backend.path '" + backendPath + "' is not in the archive."));
    }
    boolean e2ok = frontendEntryPoint != null && archive.find(frontendEntryPoint).isPresent();
    if (!e2ok) {
      errors.add(
          new CheckError(
              "bundle.paths.frontend",
              "frontend.entryPoint '" + frontendEntryPoint + "' is not in the archive."));
    }
    if (infoDoc != null) {
      Optional<ArchiveEntry> infoDocEntry = archive.find(infoDoc);
      if (infoDocEntry.isEmpty()) {
        errors.add(
            new CheckError(
                "bundle.paths.infoDoc", "infoDoc '" + infoDoc + "' is not in the archive."));
      } else if (infoDocEntry.get().data().length > uploadProperties.maxInfoDocSize().toBytes()) {
        errors.add(
            new CheckError(
                "bundle.paths.infoDoc",
                "infoDoc exceeds " + uploadProperties.maxInfoDocSize() + "."));
      }
    }
    if ("jvm".equals(runtime)) {
      boolean allowed = "OFFICIAL".equals(game.badge()) && game.ownerUserId() == null;
      if (!allowed) {
        errors.add(
            new CheckError(
                "bundle.runtime.trust",
                "runtime 'jvm' is accepted only for an Official, ownerless game (ADR-0006)."));
      }
    }
    GdkCompatibilityProperties.Range range = gdkProperties.range();
    if (gdkApiVersion == null
        || !VersionOrdering.isValidFormat(gdkApiVersion)
        || !range.contains(VersionOrdering.Version.parse(gdkApiVersion))) {
      errors.add(
          new CheckError(
              "manifest.gdkApiVersion",
              "gdkApiVersion '%s' is outside the platform's supported range [%s, %s]."
                  .formatted(gdkApiVersion, range.low(), range.high())));
    }
    if (frontendEntryPoint != null
        && !(frontendEntryPoint.endsWith(".js") || frontendEntryPoint.endsWith(".mjs"))) {
      warnings.add(
          new CheckError("frontend.entryType", "frontend.entryPoint should end in .js or .mjs."));
    }
    if (frontendEntryPoint != null && hasHtmlInFrontendTree(archive, frontendEntryPoint)) {
      warnings.add(
          new CheckError(
              "frontend.entryType",
              "The frontend tree contains an .html file, which is never served."));
    }

    // Phase F — jvm only, requires E1 and E2.
    if ("jvm".equals(runtime)
        && e1ok
        && e2ok
        && backendPath != null
        && backendEntryPointClassName != null) {
      byte[] backendJarBytes = archive.find(backendPath).orElseThrow().data();
      BackendJarAnalyzer.Result f =
          backendJarAnalyzer.analyze(backendJarBytes, backendEntryPointClassName, limits);
      errors.addAll(f.errors());
      warnings.addAll(f.warnings());
      if (f.errors().isEmpty() && f.entryClassInternalName() != null && f.innerArchive() != null) {
        ArchiveReadResult innerArchive = f.innerArchive();
        String entryClassInternalName = f.entryClassInternalName();
        ClassNode entryClassNode =
            ClassFileUtil.readClassNode(
                innerArchive.find(entryClassInternalName + ".class").orElseThrow().data());
        String stateType =
            stateSerializabilityAnalyzer.resolveStateType(entryClassNode, innerArchive);
        if (stateType == null) {
          warnings.add(
              new CheckError(
                  "state.type",
                  "The state type argument S of TurnBaseGame<S> is not statically resolvable (raw"
                      + " usage or a type variable)."));
        } else {
          StateSerializabilityAnalyzer.Result stateResult =
              stateSerializabilityAnalyzer.analyze(stateType, innerArchive);
          errors.addAll(stateResult.errors());
          warnings.addAll(stateResult.warnings());
        }
      }
    }

    if (!errors.isEmpty()) {
      repository.markFailed(versionId, errors, warnings);
      return;
    }
    if (backendPath == null || gdkApiVersion == null) {
      // Unreachable: E1 and E5 above would already have populated `errors`. Defensive only, and
      // it is what lets the compiler see backendPath/gdkApiVersion as non-null below.
      repository.markFailed(
          versionId,
          List.of(
              new CheckError(
                  "pipeline.interrupted", "Internal validation state was inconsistent.")),
          warnings);
      return;
    }

    // Phase G.
    PublicationService.Outcome outcome =
        publicationService.publishAndActivate(
            gameId,
            versionId,
            row.version(),
            manifestNode,
            manifestText,
            gdkApiVersion,
            archive,
            backendPath,
            infoDoc);
    if (!outcome.activated()) {
      repository.markFailed(versionId, outcome.errors(), warnings);
    }
  }

  private static boolean hasHtmlInFrontendTree(
      ArchiveReadResult archive, String frontendEntryPoint) {
    int slash = frontendEntryPoint.lastIndexOf('/');
    String prefix = slash >= 0 ? frontendEntryPoint.substring(0, slash + 1) : "";
    return archive.entries().stream()
        .anyMatch(
            e ->
                !e.directory()
                    && e.name().endsWith(".html")
                    && (prefix.isEmpty() ? !e.name().contains("/") : e.name().startsWith(prefix)));
  }

  private static String decodeStrictUtf8(byte[] bytes) throws CharacterCodingException {
    return StandardCharsets.UTF_8
        .newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString();
  }
}
