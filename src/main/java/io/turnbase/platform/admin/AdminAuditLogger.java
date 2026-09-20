package io.turnbase.platform.admin;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/**
 * {@code admin-rest-api.md} §2 / NFR-AUD-001 — one structured log entry per mutating admin action,
 * naming the action and the target game (timestamp is already every JSON log line's own field,
 * {@code observability.md} §3). {@code gameId} is one of the five correlation-ID MDC keys {@code
 * logback-spring.xml} already recognizes, so this reuses that field rather than inventing another.
 */
@Component
class AdminAuditLogger {

  private static final Logger log = LoggerFactory.getLogger("io.turnbase.platform.admin.audit");
  private static final String GAME_ID_MDC_KEY = "gameId";

  void log(String action, UUID gameId) {
    String previous = MDC.get(GAME_ID_MDC_KEY);
    MDC.put(GAME_ID_MDC_KEY, gameId.toString());
    try {
      log.info("Admin action: {}", action);
    } finally {
      if (previous == null) {
        MDC.remove(GAME_ID_MDC_KEY);
      } else {
        MDC.put(GAME_ID_MDC_KEY, previous);
      }
    }
  }
}
