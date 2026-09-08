package io.turnbase.platform.account;

/** {@code PATCH /api/users/me}'s {@code 200} body — the resulting username and email. */
record IdentityResponse(String username, String email) {}
