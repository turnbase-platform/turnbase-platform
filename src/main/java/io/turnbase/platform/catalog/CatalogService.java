package io.turnbase.platform.catalog;

import io.turnbase.platform.common.GoneException;
import io.turnbase.platform.common.NotFoundException;
import io.turnbase.platform.common.PageResponse;
import io.turnbase.platform.common.PaginationMetadata;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

/** {@code catalog-rest-api.md} §3, §4. */
@Service
class CatalogService {

  private final CatalogRepository repository;

  CatalogService(CatalogRepository repository) {
    this.repository = repository;
  }

  /** {@code catalog-rest-api.md} §3. */
  PageResponse<CatalogRowResponse> list(
      @Nullable UUID viewerId, CatalogListFilter filter, Pageable pageable) {
    List<CatalogRowResponse> content = repository.listGames(viewerId, filter);
    long total = repository.countGames(viewerId, filter);
    return new PageResponse<>(
        content,
        new PaginationMetadata(
            pageable.getPageNumber() + 1,
            pageable.getPageSize(),
            total,
            (int) Math.ceil((double) total / pageable.getPageSize())));
  }

  /**
   * {@code catalog-rest-api.md} §4: soft-deleted is {@code 410} (FR-GDET-007), checked before
   * visibility; everything else invisible to this caller is a {@code 404} indistinguishable from
   * nonexistent (NFR-SEC-002, FR-GDET-008).
   */
  GameDetailResponse detail(UUID gameId, @Nullable UUID viewerId) {
    boolean deleted =
        repository
            .isSoftDeleted(gameId)
            .orElseThrow(() -> new NotFoundException("Game not found."));
    if (deleted) {
      throw new GoneException("This game no longer exists.");
    }
    CatalogRepository.GameDetailRow row =
        repository
            .findVisibleDetail(gameId, viewerId)
            .orElseThrow(() -> new NotFoundException("Game not found."));
    List<String> tags = repository.findTags(gameId);
    long activePlayerCount = repository.countActivePlayers(gameId);
    return new GameDetailResponse(
        row.gameId(),
        row.title(),
        row.authorUsername(),
        row.shortDescription(),
        row.infoDocMarkdown(),
        row.minPlayers(),
        row.maxPlayers(),
        row.supportsAi(),
        row.supportsSpectators(),
        tags,
        row.version(),
        row.badge(),
        activePlayerCount,
        row.createdAt(),
        row.updatedAt());
  }
}
