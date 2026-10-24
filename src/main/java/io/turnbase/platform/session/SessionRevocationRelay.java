package io.turnbase.platform.session;

import io.turnbase.platform.common.UserPushBus;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * {@code IMP-M18-08} / {@code load-testing-phase-b.md} §4.2: a revocation raised on one Platform
 * instance closes the user's WebSocket and SSE connections on every instance. The local registries
 * already react to {@link SessionRevokedEvent}; this relays the event to the other instances over
 * {@link UserPushBus} and re-publishes what arrives from them as a {@code remote} event.
 */
@Component
class SessionRevocationRelay {

  static final String CHANNEL = "session-revoked";

  private final UserPushBus bus;
  private final ApplicationEventPublisher events;

  SessionRevocationRelay(UserPushBus bus, ApplicationEventPublisher events) {
    this.bus = bus;
    this.events = events;
    bus.subscribe(CHANNEL, this::onRemote);
  }

  @EventListener
  void onLocal(SessionRevokedEvent event) {
    if (event.remote()) {
      return;
    }
    bus.publish(
        CHANNEL,
        Map.of(
            "sessionId", event.sessionId().toString(),
            "userId", event.userId().toString(),
            "reason", event.reason()));
  }

  private void onRemote(Map<String, Object> payload) {
    events.publishEvent(
        new SessionRevokedEvent(
            UUID.fromString((String) payload.get("sessionId")),
            UUID.fromString((String) payload.get("userId")),
            Objects.requireNonNull((String) payload.get("reason")),
            true));
  }
}
