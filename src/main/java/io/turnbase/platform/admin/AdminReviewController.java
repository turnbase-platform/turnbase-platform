package io.turnbase.platform.admin;

import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** {@code admin-rest-api.md} §5.3 (approve), §5.4 (reject). */
@RestController
class AdminReviewController {

  private final AdminReviewService service;

  AdminReviewController(AdminReviewService service) {
    this.service = service;
  }

  @PostMapping("/admin/games/{gameId}/approve")
  ResponseEntity<Void> approve(@PathVariable UUID gameId) {
    service.approve(gameId);
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/admin/games/{gameId}/reject")
  ResponseEntity<Void> reject(@PathVariable UUID gameId, @RequestBody RejectRequest request) {
    service.reject(gameId, request);
    return ResponseEntity.noContent().build();
  }
}
