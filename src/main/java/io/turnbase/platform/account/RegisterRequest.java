package io.turnbase.platform.account;

import jakarta.validation.constraints.NotBlank;

/** {@code POST /api/auth/register} — {@code flows/01-arrival-and-account.md} §D step 1. */
record RegisterRequest(
    @NotBlank String username,
    @NotBlank String email,
    @NotBlank String password,
    @NotBlank String confirmPassword) {}
