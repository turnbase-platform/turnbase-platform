package io.turnbase.platform.account;

import org.jspecify.annotations.Nullable;

/**
 * {@code PATCH /api/users/me/privacy} (`rest-conventions.md`) — a partial {@code
 * user_privacy_settings} representation (FR-PROF-004, FR-PROF-006, FR-PROF-008). Every field is
 * optional; {@code null} means "leave unchanged."
 */
record UpdatePrivacyRequest(
    @Nullable Boolean profilePrivate,
    @Nullable String historyVisibility,
    @Nullable String statsVisibility,
    @Nullable String gamesVisibility,
    @Nullable String friendsVisibility,
    @Nullable Boolean blockFriendRequests) {}
