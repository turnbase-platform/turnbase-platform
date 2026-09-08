package io.turnbase.platform.room;

import java.util.UUID;

/** {@code room-rest-api.md} §5 FR-JOIN-007 — the room the caller was released from, if any. */
record ReleasedRoomResponse(UUID roomId, String reason) {}
