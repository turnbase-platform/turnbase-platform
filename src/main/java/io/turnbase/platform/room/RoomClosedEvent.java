package io.turnbase.platform.room;

import java.util.UUID;

/**
 * Published once the transaction that moved a room to {@code CLOSED} has committed ({@code
 * RoomPresenceRepository#detachSession}, the one update every closure goes through). The ownership
 * service drops the room from its in-memory set; {@code IMP-M16-08} extends the same listener to
 * delete the room's Redis keys.
 */
record RoomClosedEvent(UUID roomId) {}
