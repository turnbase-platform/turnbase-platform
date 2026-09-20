package io.turnbase.platform.catalog;

import io.turnbase.platform.common.AuthenticatedUser;
import io.turnbase.platform.common.PageResponse;
import io.turnbase.platform.common.SortFieldValidator;
import jakarta.servlet.http.HttpServletRequest;
import java.security.Principal;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code catalog-rest-api.md} §1's two endpoints — both public, both owner-resolved via an optional
 * principal rather than requiring a session ({@code SessionAuthenticationInterceptor}'s {@code
 * /api/games} exemption).
 */
@RestController
class CatalogController {

  private static final Set<String> LIST_SORT_FIELDS =
      Set.of("title", "author", "minPlayers", "maxPlayers", "version", "createdAt", "updatedAt");

  private final CatalogService catalogService;
  private final SortFieldValidator sortFieldValidator;

  CatalogController(CatalogService catalogService, SortFieldValidator sortFieldValidator) {
    this.catalogService = catalogService;
    this.sortFieldValidator = sortFieldValidator;
  }

  /** {@code catalog-rest-api.md} §3. */
  @GetMapping("/api/games")
  ResponseEntity<PageResponse<CatalogRowResponse>> list(
      @RequestParam(required = false) @Nullable String search,
      @RequestParam(required = false) @Nullable Integer minPlayers,
      @RequestParam(required = false) @Nullable Integer maxPlayers,
      @RequestParam(required = false) @Nullable List<String> tags,
      Pageable pageable,
      HttpServletRequest servletRequest) {
    sortFieldValidator.validate(pageable.getSort(), LIST_SORT_FIELDS);
    AuthenticatedUser viewer = optionalPrincipalOf(servletRequest);
    CatalogListFilter filter =
        new CatalogListFilter(
            search,
            minPlayers,
            maxPlayers,
            tags,
            pageable.getSort(),
            pageable.getPageSize(),
            (int) pageable.getOffset());
    return ResponseEntity.ok(
        catalogService.list(viewer == null ? null : viewer.userId(), filter, pageable));
  }

  /** {@code catalog-rest-api.md} §4. */
  @GetMapping("/api/games/{gameId}")
  ResponseEntity<GameDetailResponse> detail(
      @PathVariable UUID gameId, HttpServletRequest servletRequest) {
    AuthenticatedUser viewer = optionalPrincipalOf(servletRequest);
    return ResponseEntity.ok(
        catalogService.detail(gameId, viewer == null ? null : viewer.userId()));
  }

  private static @Nullable AuthenticatedUser optionalPrincipalOf(HttpServletRequest request) {
    Principal principal = request.getUserPrincipal();
    return principal instanceof AuthenticatedUser authenticatedUser ? authenticatedUser : null;
  }
}
