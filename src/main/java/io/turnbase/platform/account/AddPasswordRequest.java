package io.turnbase.platform.account;

import jakarta.validation.constraints.NotBlank;

/** {@code POST /api/users/me/auth-methods/password} — {@code rest-conventions.md} (FR-AUTH-010). */
record AddPasswordRequest(@NotBlank String password, @NotBlank String confirmPassword) {}
