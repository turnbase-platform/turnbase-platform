package io.turnbase.platform.account;

import jakarta.validation.constraints.NotBlank;

/** {@code POST /api/auth/oauth/link-challenge} — {@code oauth-rest-api.md} §7. */
record LinkChallengeRequest(@NotBlank String password) {}
