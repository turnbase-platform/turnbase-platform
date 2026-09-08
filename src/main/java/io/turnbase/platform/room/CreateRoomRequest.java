package io.turnbase.platform.room;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** {@code POST /api/rooms} — {@code room-rest-api.md} §2. */
record CreateRoomRequest(
    @NotNull UUID gameId, @NotNull RoomVisibility visibility, @Nullable Boolean spectatingEnabled) {

  boolean spectatingRequested() {
    return Boolean.TRUE.equals(spectatingEnabled);
  }
}
