package io.turnbase.platform.session;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A row of {@code user_session} (data-model-accounts.md §3). {@code keyId} is the JWT signing key
 * the session's token was issued under, so a key retirement can revoke exactly the sessions it
 * invalidates.
 */
public record UserSession(
    UUID sessionId,
    UUID userId,
    Instant issuedAt,
    Instant expiresAt,
    @Nullable Instant revokedAt,
    @Nullable String revokedReason,
    String keyId,
    @Nullable String userAgentHash) {}
