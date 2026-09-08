package io.turnbase.platform.social;

import java.time.Instant;
import java.util.UUID;

/** {@code GET /api/friend-requests} row — {@code social-rest-api.md} §4. */
record PendingRequestResponse(
    UUID requestId,
    String direction,
    String counterpartUsername,
    Instant createdAt,
    Instant expiresAt) {}
