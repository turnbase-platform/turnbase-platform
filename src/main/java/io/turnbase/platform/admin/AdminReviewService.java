package io.turnbase.platform.admin;

import io.turnbase.platform.common.BusinessRuleException;
import io.turnbase.platform.common.NotFoundException;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** {@code admin-rest-api.md} §5.3, §5.4. */
@Service
class AdminReviewService {

  private static final String ACTION_GAME_APPROVED = "GAME_APPROVED";
  private static final String ACTION_GAME_REJECTED = "GAME_REJECTED";

  private final AdminGameRepository repository;
  private final AdminAuditLogger auditLogger;

  AdminReviewService(AdminGameRepository repository, AdminAuditLogger auditLogger) {
    this.repository = repository;
    this.auditLogger = auditLogger;
  }

  void approve(UUID gameId) {
    var unused =
        repository
            .approve(gameId)
            .orElseThrow(() -> new NotFoundException("No open review for this game."));
    auditLogger.log(ACTION_GAME_APPROVED, gameId);
  }

  void reject(UUID gameId, RejectRequest request) {
    String reason = request.reason();
    if (reason == null || reason.isBlank()) {
      throw new BusinessRuleException("A rejection reason is required.");
    }
    var unused =
        repository
            .reject(gameId, reason)
            .orElseThrow(() -> new NotFoundException("No open review for this game."));
    auditLogger.log(ACTION_GAME_REJECTED, gameId);
  }
}
