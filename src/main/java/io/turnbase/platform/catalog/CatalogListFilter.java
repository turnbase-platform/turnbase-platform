package io.turnbase.platform.catalog;

import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Sort;

/** {@code catalog-rest-api.md} §3 — filters, sort and paging for {@code GET /api/games}. */
record CatalogListFilter(
    @Nullable String search,
    @Nullable Integer minPlayers,
    @Nullable Integer maxPlayers,
    @Nullable List<String> tags,
    @Nullable String owner,
    Sort sort,
    int pageSize,
    int offset) {}
