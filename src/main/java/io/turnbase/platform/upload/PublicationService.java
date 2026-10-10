package io.turnbase.platform.upload;

import io.turnbase.bundle.ArchiveEntry;
import io.turnbase.bundle.ArchiveReadResult;
import io.turnbase.platform.common.CheckError;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code bundle-format.md} §5 — Phase G. Unpacks to two object-store trees under a deny list
 * (§5.2), writes then verifies the count of what was written (§5.3 steps 1-3), then runs the §5.4
 * activation transaction under the {@code game} row lock.
 */
@Service
class PublicationService {

  private static final Logger log = LoggerFactory.getLogger(PublicationService.class);

  private static final Set<String> DENIED_EXTENSIONS = Set.of(".jar", ".war", ".class");

  private final ArtifactStore artifactStore;
  private final GameVersionRepository repository;
  private final JsonMapper jsonMapper;

  PublicationService(
      ArtifactStore artifactStore, GameVersionRepository repository, JsonMapper jsonMapper) {
    this.jsonMapper = jsonMapper;
    this.artifactStore = artifactStore;
    this.repository = repository;
  }

  record Outcome(boolean activated, List<CheckError> errors) {
    static final Outcome ACTIVATED = new Outcome(true, List.of());
  }

  Outcome publishAndActivate(
      UUID gameId,
      UUID versionId,
      String version,
      String manifestJson,
      String gdkApiVersion,
      ArchiveReadResult archive,
      String backendPath,
      @Nullable String infoDocPath) {

    int publishedCount = 0;
    for (ArchiveEntry entry : archive.entries()) {
      if (entry.directory() || isDenied(entry.name(), backendPath, infoDocPath)) {
        continue;
      }
      artifactStore.put(
          ObjectKeys.unpacked(gameId, version, entry.name()),
          entry.data(),
          contentTypeOf(entry.name()));
      publishedCount++;
    }
    Optional<ArchiveEntry> backendEntry = archive.find(backendPath);
    if (backendEntry.isEmpty()) {
      // Already guarded by E1 before this method is called; defensive only.
      return new Outcome(
          false,
          List.of(
              new CheckError(
                  "pipeline.interrupted", "backend.path entry vanished before publication.")));
    }
    artifactStore.put(
        ObjectKeys.backendJar(gameId, version),
        backendEntry.get().data(),
        "application/java-archive");

    // §5.3 step 3: write-then-verify. A count re-list is a cheaper, still-real check that the
    // half-unpacked-tree failure mode (a crash mid-write) is caught before anything can serve from
    // this version — a per-object byte-for-byte re-verification is a further hardening this item
    // leaves for later; see the session report.
    List<String> written = artifactStore.listKeys(ObjectKeys.unpackedPrefix(gameId, version));
    if (written.size() != publishedCount) {
      log.error(
          "Publication write-verify mismatch for {}/{}: wrote {} objects, listed {}.",
          gameId,
          version,
          publishedCount,
          written.size());
      return new Outcome(
          false,
          List.of(
              new CheckError(
                  "pipeline.interrupted",
                  "Publication could not be verified: expected %d unpacked objects, found %d."
                      .formatted(publishedCount, written.size()))));
    }

    GameVersionRepository.ActivationRequest request =
        buildActivationRequest(
            gameId, versionId, version, manifestJson, gdkApiVersion, archive, infoDocPath);
    Optional<GameVersionRepository.ActivationOutcome> outcome = repository.activate(request);
    if (outcome.isEmpty()) {
      return new Outcome(
          false,
          List.of(
              new CheckError(
                  "version.ordering",
                  ("The active version changed to one at least as high as %s while this bundle"
                          + " was validating.")
                      .formatted(version))));
    }
    return Outcome.ACTIVATED;
  }

  private GameVersionRepository.ActivationRequest buildActivationRequest(
      UUID gameId,
      UUID versionId,
      String version,
      String manifestJson,
      String gdkApiVersion,
      ArchiveReadResult archive,
      @Nullable String infoDocPath) {
    JsonNode manifest = jsonMapper.readTree(manifestJson);
    int minPlayers = 0;
    int maxPlayers = 0;
    for (JsonNode template : manifest.path("teamTemplates")) {
      int sizeMin = template.path("size").path("min").asInt(0);
      int sizeMax = template.path("size").path("max").asInt(sizeMin);
      int countMin = template.path("count").path("min").asInt(0);
      int countMax = template.path("count").path("max").asInt(countMin);
      minPlayers += sizeMin * countMin;
      maxPlayers += sizeMax * countMax;
    }
    boolean supportsAi = manifest.path("capabilities").path("ai").asBoolean(false);
    boolean supportsSpectators = manifest.path("capabilities").path("spectators").asBoolean(false);
    boolean supportsMidGameJoin =
        manifest.path("capabilities").path("midGameJoin").asBoolean(false);

    List<String> tags = new ArrayList<>();
    for (JsonNode tag : manifest.path("tags")) {
      tags.add(tag.asText());
    }

    String title = manifest.path("title").asText("Untitled");
    String shortDescription = manifest.path("shortDescription").asText("");
    String infoDocMarkdown =
        infoDocPath == null
            ? null
            : archive
                .find(infoDocPath)
                .map(e -> new String(e.data(), StandardCharsets.UTF_8))
                .orElse(null);

    return new GameVersionRepository.ActivationRequest(
        gameId,
        versionId,
        version,
        manifestJson,
        gdkApiVersion,
        minPlayers,
        maxPlayers,
        supportsAi,
        supportsSpectators,
        supportsMidGameJoin,
        List.copyOf(tags),
        title,
        shortDescription,
        infoDocMarkdown);
  }

  private static boolean isDenied(String name, String backendPath, @Nullable String infoDocPath) {
    if (name.equals("manifest.json") || name.equals(backendPath) || name.equals(infoDocPath)) {
      return true;
    }
    for (String ext : DENIED_EXTENSIONS) {
      if (name.endsWith(ext)) {
        return true;
      }
    }
    return false;
  }

  private static String contentTypeOf(String name) {
    int dot = name.lastIndexOf('.');
    String ext = dot >= 0 ? name.substring(dot).toLowerCase(java.util.Locale.ROOT) : "";
    return switch (ext) {
      case ".js", ".mjs" -> "text/javascript";
      case ".css" -> "text/css";
      case ".json" -> "application/json";
      case ".wasm" -> "application/wasm";
      case ".png" -> "image/png";
      case ".jpg", ".jpeg" -> "image/jpeg";
      case ".gif" -> "image/gif";
      case ".svg" -> "image/svg+xml";
      case ".webp" -> "image/webp";
      case ".ico" -> "image/x-icon";
      case ".woff" -> "font/woff";
      case ".woff2" -> "font/woff2";
      case ".ttf" -> "font/ttf";
      case ".otf" -> "font/otf";
      case ".mp3" -> "audio/mpeg";
      case ".ogg" -> "audio/ogg";
      case ".wav" -> "audio/wav";
      case ".html" -> "text/html";
      default -> "application/octet-stream";
    };
  }
}
