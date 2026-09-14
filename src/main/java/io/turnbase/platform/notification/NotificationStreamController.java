package io.turnbase.platform.notification;

import io.turnbase.platform.common.AuthenticatedUser;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** {@code sse-notifications.md} § Endpoint — the real-time half of the notification center. */
@RestController
class NotificationStreamController {

  private final NotificationEmitterRegistry registry;
  private final NotificationRepository repository;

  NotificationStreamController(
      NotificationEmitterRegistry registry, NotificationRepository repository) {
    this.registry = registry;
    this.repository = repository;
  }

  /**
   * No timeout ({@code 0L}): the connection is long-lived by design, and {@code
   * NotificationEmitterRegistry}'s keepalive comment is what keeps intermediary proxies from
   * closing it on idle.
   */
  @GetMapping(path = "/api/notifications/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  SseEmitter stream(HttpServletRequest servletRequest) {
    AuthenticatedUser principal = NotificationController.principalOf(servletRequest);
    SseEmitter emitter = new SseEmitter(0L);
    String emitterId = registry.register(principal, emitter);

    List<NotificationRepository.UndeliveredRow> undelivered =
        repository.markUndeliveredAsDelivered(principal.userId());
    for (NotificationRepository.UndeliveredRow row : undelivered) {
      registry.sendCatchUpNotification(
          emitterId,
          row.notificationId(),
          row.type(),
          row.payload(),
          row.createdAt(),
          row.eventSeq());
    }

    return emitter;
  }
}
