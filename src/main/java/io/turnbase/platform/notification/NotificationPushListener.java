package io.turnbase.platform.notification;

import io.turnbase.platform.common.UserPushBus;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Pushes a freshly committed notification to its recipient's open streams. A recipient with no open
 * stream is left untouched — the row stays undelivered for the catch-up on their next open ({@code
 * FR-NOTIF-008}). {@code fallbackExecution} covers a writer that ran outside a transaction, where
 * the insert was already committed.
 *
 * <p>The recipient's stream may be open on another Platform instance ({@code IMP-M18-08}): the
 * commit is announced over {@link UserPushBus} and whichever instance holds a stream claims the row
 * and pushes it. The claim is the same guarded update, so a row is sent once however many instances
 * race for it.
 */
@Component
class NotificationPushListener {

  private final NotificationEmitterRegistry registry;
  private final NotificationRepository repository;

  private static final String CHANNEL = "notification";

  private final UserPushBus bus;

  NotificationPushListener(
      NotificationEmitterRegistry registry, NotificationRepository repository, UserPushBus bus) {
    this.registry = registry;
    this.repository = repository;
    this.bus = bus;
    bus.subscribe(
        CHANNEL,
        message ->
            push(
                UUID.fromString((String) message.get("userId")),
                UUID.fromString((String) message.get("notificationId"))));
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
  void onPersisted(NotificationPersistedEvent event) {
    push(event.userId(), event.notificationId());
    bus.publish(
        CHANNEL,
        Map.of(
            "userId", event.userId().toString(),
            "notificationId", event.notificationId().toString()));
  }

  private void push(UUID userId, UUID notificationId) {
    if (!registry.hasOpenStream(userId)) {
      return;
    }
    // The claim is a guarded update: a stream opening concurrently runs the same statement for
    // the catch-up, and whichever stamps the row sends it — never both.
    Optional<NotificationRepository.UndeliveredRow> row = repository.markDelivered(notificationId);
    row.ifPresent(
        r ->
            registry.pushNotification(
                userId, r.notificationId(), r.type(), r.payload(), r.createdAt(), r.eventSeq()));
  }
}
