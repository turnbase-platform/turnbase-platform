package io.turnbase.platform.room;

import io.turnbase.platform.common.GameOriginMatcher;
import io.turnbase.platform.common.GameResponseHeaders;
import io.turnbase.platform.common.NotFoundException;
import io.turnbase.platform.common.PlatformOriginProperties;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.HtmlUtils;

/**
 * {@code game-asset-serving.md} §5's bootstrap document. The session id is the only input: the
 * bundle is whichever {@code (gameId, version)} the room pinned at creation, so every reconnecting
 * player receives the identical bundle regardless of what is active in the catalog now. Unknown and
 * ended sessions, and a {@code Host} naming a different game, are all the same 404.
 */
@RestController
class GamePlayController {

  private final RoomGameStartRepository repository;
  private final GameOriginMatcher originMatcher;
  private final GameResponseHeaders gameResponseHeaders;
  private final PlatformOriginProperties platformOrigin;

  GamePlayController(
      RoomGameStartRepository repository,
      GameOriginMatcher originMatcher,
      GameResponseHeaders gameResponseHeaders,
      PlatformOriginProperties platformOrigin) {
    this.repository = repository;
    this.originMatcher = originMatcher;
    this.gameResponseHeaders = gameResponseHeaders;
    this.platformOrigin = platformOrigin;
  }

  @GetMapping("/play/{sessionId}")
  ResponseEntity<byte[]> play(
      @PathVariable UUID sessionId, @RequestHeader(HttpHeaders.HOST) String host) {
    RoomRecords.PlayTarget target =
        repository
            .playTarget(sessionId)
            .filter(t -> originMatcher.hostServesGame(host, t.gameId().toString()))
            .orElseThrow(() -> new NotFoundException("Not found."));

    String nonce = gameResponseHeaders.newNonce();
    String html =
        """
        <!doctype html>
        <html>
          <head>
            <meta charset="utf-8">
            <base href="/g/%s/%s/">
            <script nonce="%s">
              window.__TURNBASE__ = { parentOrigin: "%s", sessionId: "%s" };
            </script>
          </head>
          <body>
            <script type="module" src="%s"></script>
          </body>
        </html>
        """
            .formatted(
                target.gameId(),
                HtmlUtils.htmlEscape(target.version()),
                nonce,
                jsString(platformOrigin.origin()),
                sessionId,
                HtmlUtils.htmlEscape(target.frontendEntryPoint()));
    return ResponseEntity.ok()
        .contentType(MediaType.TEXT_HTML)
        .header("Cache-Control", "no-store")
        .header("X-Content-Type-Options", "nosniff")
        .header("Content-Security-Policy", gameResponseHeaders.csp(nonce))
        .body(html.getBytes(StandardCharsets.UTF_8));
  }

  private static String jsString(String value) {
    return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("<", "\\u003c");
  }
}
