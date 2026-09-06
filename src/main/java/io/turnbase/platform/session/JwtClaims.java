package io.turnbase.platform.session;

import java.time.Instant;
import java.util.UUID;

/**
 * secrets-and-configuration.md §3.5 — identity only. Everything else (username, role, privacy
 * settings) is read from the database on the request rather than cached in the token, because a
 * claim cannot be invalidated the way a database row can.
 */
public record JwtClaims(
    UUID userId, UUID sessionId, String keyId, Instant issuedAt, Instant expiresAt) {}
