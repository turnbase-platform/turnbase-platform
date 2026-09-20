package io.turnbase.platform.admin;

import io.turnbase.platform.common.NotFoundException;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** {@code admin-rest-api.md} §3, §5.1, §5.2. */
@Service
class AdminGameService {

  private static final String ACTION_GAME_CREATED = "GAME_CREATED";

  private final AdminGameRepository repository;
  private final AdminAuditLogger auditLogger;

  AdminGameService(AdminGameRepository repository, AdminAuditLogger auditLogger) {
    this.repository = repository;
    this.auditLogger = auditLogger;
  }

  UUID create() {
    UUID gameId = repository.insertGame();
    auditLogger.log(ACTION_GAME_CREATED, gameId);
    return gameId;
  }

  List<AdminGameRepository.PendingReview> listPending() {
    return repository.listPending();
  }

  AdminGameRepository.GameDetail detail(UUID gameId) {
    return repository.findDetail(gameId).orElseThrow(() -> new NotFoundException("No such game."));
  }
}
