package io.turnbase.platform.social;

import jakarta.validation.constraints.NotBlank;

/** {@code POST /api/friend-requests} — {@code social-rest-api.md} §3. */
record SendFriendRequestRequest(@NotBlank String recipientUsername) {}
