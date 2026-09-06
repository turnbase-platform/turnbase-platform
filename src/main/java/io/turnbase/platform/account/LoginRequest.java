package io.turnbase.platform.account;

import jakarta.validation.constraints.NotBlank;

/** {@code POST /api/auth/login} — FR-AUTH-006, username or email. */
record LoginRequest(@NotBlank String usernameOrEmail, @NotBlank String password) {}
