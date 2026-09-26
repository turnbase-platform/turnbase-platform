package io.turnbase.platform.seed;

import io.turnbase.platform.upload.CreateVersionRequest;
import io.turnbase.platform.upload.CreateVersionResponse;
import io.turnbase.platform.upload.VersionResource;
import io.turnbase.platform.upload.VersionUploadService;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * {@code local-environment.md} §6: every {@code <gameId>.zip} in {@code turnbase.seed.bundles-dir}
 * goes through the real upload pipeline — slot, {@code PUT}, confirm, validate, activate — never
 * around it. The seeder is the official deployment path, so it confirms with {@code
 * arrivedViaAdminListener = true} ({@code bundle-format.md} §1.1, E4).
 */
@Component
class GameBundleSeeder {

  private static final Logger log = LoggerFactory.getLogger(GameBundleSeeder.class);
  private static final Duration ACTIVATION_TIMEOUT = Duration.ofSeconds(90);
  private static final Duration POLL_INTERVAL = Duration.ofMillis(250);

  private final SeedProperties properties;
  private final SeedRepository repository;
  private final VersionUploadService uploadService;
  private final HttpClient httpClient = HttpClient.newHttpClient();

  GameBundleSeeder(
      SeedProperties properties, SeedRepository repository, VersionUploadService uploadService) {
    this.properties = properties;
    this.repository = repository;
    this.uploadService = uploadService;
  }

  void apply() throws IOException, InterruptedException {
    String dir = properties.bundlesDir();
    if (dir == null || dir.isBlank()) {
      log.info("turnbase.seed.bundles-dir is unset; no official games seeded.");
      return;
    }
    Path directory = Path.of(dir);
    if (!Files.isDirectory(directory)) {
      log.info("Seed bundle directory {} does not exist; no official games seeded.", directory);
      return;
    }
    List<Path> bundles;
    try (Stream<Path> files = Files.list(directory)) {
      bundles = files.filter(p -> p.getFileName().toString().endsWith(".zip")).sorted().toList();
    }
    for (Path bundle : bundles) {
      seed(bundle);
    }
  }

  private void seed(Path bundle) throws IOException, InterruptedException {
    String name = bundle.getFileName().toString();
    UUID gameId = UUID.fromString(name.substring(0, name.length() - ".zip".length()));

    repository.insertOfficialGame(gameId);
    if (repository.hasActiveVersion(gameId)) {
      log.info("Official game {} already has an active version; skipping {}.", gameId, name);
      return;
    }

    byte[] content = Files.readAllBytes(bundle);
    String version = readVersion(content, name);
    CreateVersionResponse slot =
        uploadService.createSlot(gameId, new CreateVersionRequest(version, content.length));
    put(slot.uploadUrl(), content);
    uploadService.confirm(gameId, slot.uploadId(), true);

    VersionResource resource = awaitTerminal(gameId, slot.uploadId());
    if (!"ACTIVE".equals(String.valueOf(resource.status()))) {
      throw new IllegalStateException(
          "Seed bundle %s did not activate: status %s, errors %s"
              .formatted(name, resource.status(), resource.errors()));
    }
    log.info("Seeded official game {} version {} from {}.", gameId, version, name);
  }

  private void put(URI url, byte[] content) throws IOException, InterruptedException {
    HttpResponse<Void> response =
        httpClient.send(
            HttpRequest.newBuilder(url)
                .PUT(HttpRequest.BodyPublishers.ofByteArray(content))
                .build(),
            HttpResponse.BodyHandlers.discarding());
    if (response.statusCode() / 100 != 2) {
      throw new IllegalStateException(
          "PUT of the seed bundle to its pre-signed URL failed with " + response.statusCode());
    }
  }

  private VersionResource awaitTerminal(UUID gameId, UUID uploadId) throws InterruptedException {
    Instant deadline = Instant.now().plus(ACTIVATION_TIMEOUT);
    while (true) {
      VersionResource resource = uploadService.get(gameId, uploadId);
      String status = String.valueOf(resource.status());
      if (!status.equals("PENDING") && !status.equals("PROCESSING")) {
        return resource;
      }
      if (Instant.now().isAfter(deadline)) {
        throw new IllegalStateException("Seed bundle upload " + uploadId + " never finished.");
      }
      Thread.sleep(POLL_INTERVAL);
    }
  }

  /** The slot needs the version before Phase D can read it from the manifest. */
  private static String readVersion(byte[] zip, String name) throws IOException {
    try (var in = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(zip))) {
      for (var entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
        if (entry.getName().equals("manifest.json")) {
          var manifest = new com.fasterxml.jackson.databind.ObjectMapper().readTree(in);
          return manifest.path("version").asText();
        }
      }
    }
    throw new IllegalStateException("Seed bundle " + name + " has no manifest.json at its root.");
  }
}
