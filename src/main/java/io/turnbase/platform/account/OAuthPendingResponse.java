package io.turnbase.platform.account;

/** {@code GET /api/auth/oauth/pending} — {@code oauth-rest-api.md} §5. */
record OAuthPendingResponse(String email) {}
