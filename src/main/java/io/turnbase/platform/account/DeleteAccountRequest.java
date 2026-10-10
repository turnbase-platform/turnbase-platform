package io.turnbase.platform.account;

import org.jspecify.annotations.Nullable;

/**
 * {@code DELETE /api/users/me} body (`rest-conventions.md`, {@code IMP-GATE-99}); absent for an
 * account with no password method.
 */
record DeleteAccountRequest(@Nullable String currentPassword) {}
