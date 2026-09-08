package io.turnbase.platform.account;

import java.util.UUID;

/**
 * The response body of {@code /api/auth/register}, {@code /api/auth/login} and {@code GET
 * /api/auth/session}.
 */
record PrincipalResponse(UUID userId, String username) {}
