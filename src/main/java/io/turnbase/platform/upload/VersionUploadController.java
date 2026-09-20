package io.turnbase.platform.upload;

import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code file-uploads.md}'s three-step flow. {@code rate-limiting.md} Tier 3 is already wired
 * against the exact {@code POST /api/games/{gameId}/versions} path by {@code
 * common.RateLimitInterceptor} (IMP-M1-02) — nothing here needs to touch it.
 */
@RestController
class VersionUploadController {

  private final VersionUploadService service;

  VersionUploadController(VersionUploadService service) {
    this.service = service;
  }

  /** {@code file-uploads.md} step 1; {@code bundle-format.md} §3.2 Phase A. */
  @PostMapping("/api/games/{gameId}/versions")
  ResponseEntity<CreateVersionResponse> createSlot(
      @PathVariable UUID gameId, @Valid @RequestBody CreateVersionRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED).body(service.createSlot(gameId, request));
  }

  /**
   * {@code file-uploads.md} step 3; {@code bundle-format.md} §3.2 Phase B1 synchronous, Phases C-G
   * asynchronous.
   */
  @PostMapping("/api/games/{gameId}/versions/{uploadId}/confirm")
  ResponseEntity<Void> confirm(@PathVariable UUID gameId, @PathVariable UUID uploadId) {
    service.confirm(gameId, uploadId, false);
    return ResponseEntity.accepted().build();
  }

  /** {@code file-uploads.md} step 4; {@code bundle-format.md} §3.4 — always {@code 200}. */
  @GetMapping("/api/games/{gameId}/versions/{uploadId}")
  ResponseEntity<VersionResource> get(@PathVariable UUID gameId, @PathVariable UUID uploadId) {
    return ResponseEntity.ok(service.get(gameId, uploadId));
  }
}
