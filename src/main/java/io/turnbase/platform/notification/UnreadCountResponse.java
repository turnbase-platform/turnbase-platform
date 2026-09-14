package io.turnbase.platform.notification;

/** {@code GET /api/notifications/unread-count} — {@code notification-rest-api.md} §3. */
record UnreadCountResponse(long unreadCount) {}
