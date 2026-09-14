package io.turnbase.platform.notification;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The shape shared by every item in {@code GET /api/notifications} and the body of {@code PATCH
 * /api/notifications/{id}} — {@code notification-rest-api.md} §§2, 4. {@code readAt} is {@code
 * null} for an unread notification.
 */
record NotificationResponse(
    UUID notificationId,
    String type,
    Map<String, Object> payload,
    Instant createdAt,
    @Nullable Instant readAt) {}
