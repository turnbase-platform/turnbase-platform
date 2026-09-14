package io.turnbase.platform.notification;

import jakarta.validation.constraints.NotNull;

/**
 * {@code PATCH /api/notifications/{id}} — {@code notification-rest-api.md} §4. {@code read: false}
 * is rejected by {@code NotificationService}, not by a constraint here: it is a valid JSON boolean,
 * just not one this endpoint's absolute-value semantics have an operation for.
 */
record MarkReadRequest(@NotNull Boolean read) {}
