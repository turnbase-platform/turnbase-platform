package io.turnbase.platform.notification;

import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Pushes a freshly committed notification to its recipient's open streams. A recipient with no open
 * stream is left untouched — the row stays undelivered for the catch-up on their next open ({@code
 * FR-NOTIF-008}). {@code fallbackExecution} covers a writer that ran outside a transaction, where
 * the insert was already committed.
 */
@Component
class NotificationPushListener {

  private final NotificationEmitterRegistry registry;
  private final NotificationRepository repository;

  NotificationPushListener(
      NotificationEmitterRegistry registry, NotificationRepository repository) {
    this.registry = registry;
    this.repository = repository;
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
  void onPersisted(NotificationPersistedEvent event) {
    if (!registry.hasOpenStream(event.userId())) {
      return;
    }
    // The claim is a guarded update: a stream opening concurrently runs the same statement for
    // the catch-up, and whichever stamps the row sends it — never both.
    Optional<NotificationRepository.UndeliveredRow> row =
        repository.markDelivered(event.notificationId());
    row.ifPresent(
        r ->
            registry.pushNotification(
                event.userId(),
                r.notificationId(),
                r.type(),
                r.payload(),
                r.createdAt(),
                r.eventSeq()));
  }
}
