package io.turnbase.platform.room;

import io.turnbase.platform.common.PaginationMetadata;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * {@code room-rest-api.md} §3's {@code GET /api/rooms} envelope: the standard {@code content} and
 * {@code pagination} plus the caller's {@code reservedSlot}, which is always serialised (as {@code
 * null} when absent) so the client can tell "none" from "field missing".
 */
record RoomListResponse(
    List<RoomListRowResponse> content,
    PaginationMetadata pagination,
    @Nullable ReservedSlot reservedSlot) {

  /** The caller's live grace-period reservation, independent of the list's filters and page. */
  record ReservedSlot(UUID roomId, String gameName, long graceRemainingSeconds) {}
}
