package io.turnbase.platform.room;

import jakarta.validation.constraints.NotNull;

/**
 * {@code POST /api/rooms/{roomId}/participants} — {@code room-rest-api.md} §5. No default for
 * {@code role}: guessing wrong seats a player as a spectator or consumes the last player slot for
 * someone who wanted to watch, and neither is recoverable without a second call.
 */
record JoinRoomRequest(@NotNull ParticipantRole role) {}
