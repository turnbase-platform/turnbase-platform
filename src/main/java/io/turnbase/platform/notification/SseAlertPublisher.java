package io.turnbase.platform.notification;

import io.turnbase.platform.common.UserPushBus;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The real {@link AlertPublisher}, replacing {@code PendingAlertPublisher}'s no-op now that {@link
 * NotificationEmitterRegistry} exists. Routes to whatever {@code GET /api/notifications/stream}
 * connections {@code userId} currently has open, dropping silently if there are none
 * (FR-ALERT-010). The alert is also relayed to the other Platform instances ({@code IMP-M18-08}),
 * where the user's stream may be open instead.
 */
@Component
class SseAlertPublisher implements AlertPublisher {

  static final String CHANNEL = "alert";

  private final NotificationEmitterRegistry registry;
  private final UserPushBus bus;

  SseAlertPublisher(NotificationEmitterRegistry registry, UserPushBus bus) {
    this.registry = registry;
    this.bus = bus;
    bus.subscribe(
        CHANNEL,
        message ->
            registry.publishAlert(
                UUID.fromString((String) message.get("userId")),
                Objects.requireNonNull((String) message.get("type")),
                payloadOf(Objects.requireNonNull(message.get("payload")))));
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> payloadOf(Object payload) {
    return (Map<String, Object>) payload;
  }

  @Override
  public void publish(UUID userId, String type, Map<String, Object> payload) {
    registry.publishAlert(userId, type, payload);
    bus.publish(CHANNEL, Map.of("userId", userId.toString(), "type", type, "payload", payload));
  }
}
