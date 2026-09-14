package io.turnbase.platform.notification;

import java.util.Map;
import java.util.UUID;

/**
 * {@code sse-notifications.md} § Alert types — a transient, unpersisted push to one user's SSE
 * connection, dropped silently if nobody is listening (FR-ALERT-010). This is the public seam
 * another feature calls to publish one; {@link SseAlertPublisher} is the real implementation,
 * writing through {@link NotificationEmitterRegistry}.
 */
public interface AlertPublisher {

  /**
   * @param type one of {@code sse-notifications.md}'s alert {@code type} values, e.g. {@code
   *     "INVITATION_ACCEPTED"}
   * @param payload the type-specific fields of the {@code data} object, excluding {@code type}
   */
  void publish(UUID userId, String type, Map<String, Object> payload);
}
