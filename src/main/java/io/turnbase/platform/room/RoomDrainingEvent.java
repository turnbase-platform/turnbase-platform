package io.turnbase.platform.room;

import java.util.UUID;

/**
 * Published by {@link RoomDrain} for each owned room just before it is released on shutdown ({@code
 * room-lifecycle.md} §1.6 step 4): the room's sockets get {@code RECONNECT_REQUIRED}, marked
 * instance-initiated.
 */
record RoomDrainingEvent(UUID roomId) {}
