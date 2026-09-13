package io.turnbase.platform.notification;

import java.util.Map;
import java.util.UUID;

/**
 * {@code sse-notifications.md} § Alert types — a transient, unpersisted push to one user's SSE
 * connection, dropped silently if nobody is listening (FR-ALERT-010). This is the public seam
 * another feature calls to publish one; {@code IMP-M5-03} builds the real SSE dispatch this
 * delegates to. Until then {@link PendingAlertPublisher} is the only implementation, matching the
 * {@code room.SessionTerminator}/{@code room.GameSessionStarter} precedent for a call into
 * machinery that does not exist in this repository yet.
 */
public interface AlertPublisher {

  /**
   * @param type one of {@code sse-notifications.md}'s alert {@code type} values, e.g. {@code
   *     "INVITATION_ACCEPTED"}
   * @param payload the type-specific fields of the {@code data} object, excluding {@code type}
   */
  void publish(UUID userId, String type, Map<String, Object> payload);
}
