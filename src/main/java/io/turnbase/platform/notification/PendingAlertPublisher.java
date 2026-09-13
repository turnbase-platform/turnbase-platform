package io.turnbase.platform.notification;

import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The only {@link AlertPublisher} until {@code IMP-M5-03} lands. A no-op is the honest behaviour:
 * there is no {@code NotificationEmitterRegistry} or SSE stream to write to yet, and an alert that
 * is dropped when nobody is listening (FR-ALERT-010) is indistinguishable, from the caller's side,
 * from one dropped because the mechanism does not exist yet.
 */
@Component
class PendingAlertPublisher implements AlertPublisher {

  @Override
  public void publish(UUID userId, String type, Map<String, Object> payload) {
    // IMP-M5-03 replaces this with a real SSE alert push.
  }
}
