package io.turnbase.platform.notification;

import java.util.UUID;

/**
 * Published by every feature that inserts a {@code notification} row, inside the writing
 * transaction. {@link NotificationPushListener} acts on it only after commit, so a rolled-back
 * write is never pushed ({@code sse-notifications.md} § Offline delivery, {@code FR-NOTIF-007}).
 */
public record NotificationPersistedEvent(UUID notificationId, UUID userId) {}
