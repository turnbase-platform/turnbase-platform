package io.turnbase.platform.social;

import java.time.Instant;
import java.util.UUID;

/** {@code social-rest-api.md} §3's {@code REQUEST_SENT} outcome. */
record FriendRequestSummaryResponse(
    UUID requestId, String recipientUsername, Instant createdAt, Instant expiresAt) {}
