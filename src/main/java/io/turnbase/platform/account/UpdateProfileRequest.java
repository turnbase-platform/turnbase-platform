package io.turnbase.platform.account;

import org.jspecify.annotations.Nullable;

/**
 * {@code PATCH /api/users/me} (`rest-conventions.md`) — username and/or email, either or both.
 * {@code null} means "leave unchanged," per the endpoint's partial-update contract.
 */
record UpdateProfileRequest(@Nullable String username, @Nullable String email) {}
