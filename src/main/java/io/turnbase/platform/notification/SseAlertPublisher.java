package io.turnbase.platform.notification;

import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The real {@link AlertPublisher}, replacing {@code PendingAlertPublisher}'s no-op now that {@link
 * NotificationEmitterRegistry} exists. Routes to whatever {@code GET /api/notifications/stream}
 * connections {@code userId} currently has open, dropping silently if there are none
 * (FR-ALERT-010).
 */
@Component
class SseAlertPublisher implements AlertPublisher {

  private final NotificationEmitterRegistry registry;

  SseAlertPublisher(NotificationEmitterRegistry registry) {
    this.registry = registry;
  }

  @Override
  public void publish(UUID userId, String type, Map<String, Object> payload) {
    registry.publishAlert(userId, type, payload);
  }
}
