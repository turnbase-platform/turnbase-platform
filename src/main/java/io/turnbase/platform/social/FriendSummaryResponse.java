package io.turnbase.platform.social;

import java.util.UUID;

/** The other party of a friendship — {@code social-rest-api.md} §3, §5, §7. */
record FriendSummaryResponse(UUID userId, String username) {}
