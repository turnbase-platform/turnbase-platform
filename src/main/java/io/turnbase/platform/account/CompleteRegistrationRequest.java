package io.turnbase.platform.account;

import jakarta.validation.constraints.NotBlank;

/** {@code POST /api/auth/oauth/complete-registration} — {@code oauth-rest-api.md} §6. */
record CompleteRegistrationRequest(@NotBlank String username) {}
