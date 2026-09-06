package io.turnbase.platform.account;

import java.util.UUID;

/** The response body of both {@code /api/auth/register} and {@code /api/auth/login}. */
record PrincipalResponse(UUID userId, String username) {}
