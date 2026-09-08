package io.turnbase.platform.room;

import org.jspecify.annotations.Nullable;

/** {@code room-rest-api.md} §5 — {@code releasedRoom} is {@code null} when there was none. */
record JoinRoomResponse(RoomResponse room, @Nullable ReleasedRoomResponse releasedRoom) {}
