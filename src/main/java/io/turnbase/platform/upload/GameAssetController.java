package io.turnbase.platform.upload;

import io.turnbase.platform.common.GameResponseHeaders;
import io.turnbase.platform.common.NotFoundException;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code game-asset-serving.md} §4.1's static bundle route. {@code GameOriginRoutingInterceptor}
 * (common) has already confirmed the request arrived on a game origin before this ever runs; this
 * controller only has to resolve {@code (gameId, version)} against {@code IMP-M4-03}'s publication
 * state and hand back exactly what was published — the deny list of §5.2 was already applied at
 * publish time, so nothing here re-checks it.
 */
@RestController
class GameAssetController {

  /** {@code game-asset-serving.md} §4.1's exact literal — assets are immutable, path-pinned. */
  private static final String CACHE_CONTROL = "public, max-age=31536000, immutable";

  private final ArtifactStore artifactStore;
  private final GameVersionRepository repository;
  private final GameResponseHeaders gameResponseHeaders;

  GameAssetController(
      ArtifactStore artifactStore,
      GameVersionRepository repository,
      GameResponseHeaders gameResponseHeaders) {
    this.artifactStore = artifactStore;
    this.repository = repository;
    this.gameResponseHeaders = gameResponseHeaders;
  }

  @GetMapping("/g/{gameId}/{version}/{*assetPath}")
  ResponseEntity<byte[]> serve(
      @PathVariable UUID gameId, @PathVariable String version, @PathVariable String assetPath) {
    String relativePath = assetPath.startsWith("/") ? assetPath.substring(1) : assetPath;
    if (relativePath.isBlank() || !repository.isServable(gameId, version)) {
      throw new NotFoundException("Not found.");
    }

    ArtifactStore.StoredObject object =
        artifactStore
            .get(ObjectKeys.unpacked(gameId, version, relativePath))
            .orElseThrow(() -> new NotFoundException("Not found."));

    String nonce = gameResponseHeaders.newNonce();
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(object.contentType()))
        .header("Cache-Control", CACHE_CONTROL)
        .header("X-Content-Type-Options", "nosniff")
        .header("Content-Security-Policy", gameResponseHeaders.csp(nonce))
        .body(object.data());
  }
}
