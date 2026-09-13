package io.turnbase.platform.room;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code POST /api/rooms/{roomId}/invitations} response — {@code room-invitation-rest-api.md} §2.
 */
record RoomInvitationResponse(
    UUID invitationId, String inviteeUsername, Instant createdAt, Instant expiresAt) {}
