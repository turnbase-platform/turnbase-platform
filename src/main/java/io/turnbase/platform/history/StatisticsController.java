package io.turnbase.platform.history;

import io.turnbase.platform.common.AuthenticatedUser;
import jakarta.servlet.http.HttpServletRequest;
import java.security.Principal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** {@code statistics-rest-api.md} §2 — public, privacy-resolved. */
@RestController
class StatisticsController {

  private final StatisticsService statisticsService;

  StatisticsController(StatisticsService statisticsService) {
    this.statisticsService = statisticsService;
  }

  @GetMapping("/api/users/{username}/statistics")
  StatisticsResponse statistics(@PathVariable String username, HttpServletRequest request) {
    Principal principal = request.getUserPrincipal();
    var viewer = principal instanceof AuthenticatedUser user ? user.userId() : null;
    return statisticsService.statisticsOf(username, viewer);
  }
}
