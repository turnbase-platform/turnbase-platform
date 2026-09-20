package io.turnbase.platform.admin;

import org.jspecify.annotations.Nullable;

/**
 * {@code admin-rest-api.md} §5.4. {@code reason} is checked for blank in {@link
 * AdminReviewService}, not {@code @NotBlank} — a missing reason is FR-REV-005's own business rule
 * (422 business-rule-violation), not the generic validation-failed taxonomy entry.
 */
record RejectRequest(@Nullable String reason) {}
