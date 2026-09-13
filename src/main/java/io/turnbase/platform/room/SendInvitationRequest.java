package io.turnbase.platform.room;

import jakarta.validation.constraints.NotBlank;

/** {@code POST /api/rooms/{roomId}/invitations} — {@code room-invitation-rest-api.md} §2. */
record SendInvitationRequest(@NotBlank String inviteeUsername) {}
