package io.turnbase.platform.admin;

import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code admin-rest-api.md} §3 (create), §5.1 (list pending), §5.2 (detail). */
@RestController
class AdminGameController {

  private final AdminGameService service;

  AdminGameController(AdminGameService service) {
    this.service = service;
  }

  @PostMapping("/admin/games")
  ResponseEntity<CreateGameResponse> create() {
    UUID gameId = service.create();
    return ResponseEntity.created(URI.create("/admin/games/" + gameId))
        .body(new CreateGameResponse(gameId));
  }

  @GetMapping("/admin/games")
  PendingGameListResponse listPending() {
    return PendingGameListResponse.of(service.listPending());
  }

  @GetMapping("/admin/games/{gameId}")
  GameDetailResponse detail(@PathVariable UUID gameId) {
    return GameDetailResponse.of(service.detail(gameId));
  }
}
