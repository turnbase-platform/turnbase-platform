package io.turnbase.platform.social;

import java.util.UUID;

/** {@code GET /api/users?search=} row — {@code social-rest-api.md} §2. */
record UserSearchResponse(
    UUID userId, String username, boolean profilePrivate, boolean canSendRequest) {}
