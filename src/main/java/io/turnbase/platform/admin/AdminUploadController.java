package io.turnbase.platform.admin;

import io.turnbase.platform.upload.CreateVersionRequest;
import io.turnbase.platform.upload.CreateVersionResponse;
import io.turnbase.platform.upload.VersionResource;
import io.turnbase.platform.upload.VersionUploadService;
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
 * {@code admin-rest-api.md} §4 — field-for-field the same three routes as {@code
 * file-uploads.md}'s, reusing {@code upload.VersionUploadService} verbatim (ADR-0014: the target
 * feature's public API, not a re-derivation) rather than duplicating Phases A-G. What differs is
 * this listener's own Basic auth (the admin child context's {@link AdminBasicAuthInterceptor}), the
 * audit log entry NFR-AUD-001 requires of every mutating admin route, and passing {@code true} for
 * {@code arrivedViaAdminListener} — E4's second condition, the one thing only this controller can
 * attest to.
 */
@RestController
class AdminUploadController {

  private static final String ACTION_VERSION_SLOT_CREATED = "VERSION_SLOT_CREATED";
  private static final String ACTION_VERSION_CONFIRMED = "VERSION_CONFIRMED";

  private final VersionUploadService service;
  private final AdminAuditLogger auditLogger;

  AdminUploadController(VersionUploadService service, AdminAuditLogger auditLogger) {
    this.service = service;
    this.auditLogger = auditLogger;
  }

  @PostMapping("/admin/games/{gameId}/versions")
  ResponseEntity<CreateVersionResponse> createSlot(
      @PathVariable UUID gameId, @Valid @RequestBody CreateVersionRequest request) {
    CreateVersionResponse response = service.createSlot(gameId, request);
    auditLogger.log(ACTION_VERSION_SLOT_CREATED, gameId);
    return ResponseEntity.status(HttpStatus.CREATED).body(response);
  }

  @PostMapping("/admin/games/{gameId}/versions/{uploadId}/confirm")
  ResponseEntity<Void> confirm(@PathVariable UUID gameId, @PathVariable UUID uploadId) {
    service.confirm(gameId, uploadId, true);
    auditLogger.log(ACTION_VERSION_CONFIRMED, gameId);
    return ResponseEntity.accepted().build();
  }

  @GetMapping("/admin/games/{gameId}/versions/{uploadId}")
  ResponseEntity<VersionResource> get(@PathVariable UUID gameId, @PathVariable UUID uploadId) {
    return ResponseEntity.ok(service.get(gameId, uploadId));
  }
}
