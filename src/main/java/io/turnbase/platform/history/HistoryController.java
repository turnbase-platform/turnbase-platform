package io.turnbase.platform.history;

import io.turnbase.platform.common.AuthenticatedUser;
import io.turnbase.platform.common.Canonicalizer;
import io.turnbase.platform.common.PageResponse;
import io.turnbase.platform.common.SortFieldValidator;
import jakarta.servlet.http.HttpServletRequest;
import java.security.Principal;
import java.util.Set;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** {@code history-rest-api.md} §1 — public, privacy-resolved. */
@RestController
class HistoryController {

  /** FR-HIST-024 names the only order; no {@code sort} parameter is accepted. */
  private static final Set<String> SORT_FIELDS = Set.of();

  private final HistoryService historyService;
  private final SortFieldValidator sortFieldValidator;

  HistoryController(HistoryService historyService, SortFieldValidator sortFieldValidator) {
    this.historyService = historyService;
    this.sortFieldValidator = sortFieldValidator;
  }

  @GetMapping("/api/users/{username}/history")
  ResponseEntity<PageResponse<HistoryEntryResponse>> history(
      @PathVariable String username,
      @RequestParam(required = false) String search,
      Pageable pageable,
      HttpServletRequest request) {
    sortFieldValidator.validate(pageable.getSort(), SORT_FIELDS);
    Principal principal = request.getUserPrincipal();
    var viewer = principal instanceof AuthenticatedUser user ? user.userId() : null;
    return ResponseEntity.ok(
        PageResponse.from(
            historyService.historyOf(
                username, viewer, Canonicalizer.searchTerm(search), pageable)));
  }
}
