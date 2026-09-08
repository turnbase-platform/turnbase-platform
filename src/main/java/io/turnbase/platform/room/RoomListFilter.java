package io.turnbase.platform.room;

import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Sort;

/** {@code room-rest-api.md} §3 — filters, sort and paging for {@code GET /api/rooms}. */
record RoomListFilter(
    @Nullable String search,
    @Nullable List<String> statuses,
    @Nullable Integer minPlayers,
    @Nullable Integer maxPlayers,
    @Nullable Integer playerCountMin,
    @Nullable Integer playerCountMax,
    Sort sort,
    int pageSize,
    int offset) {}
