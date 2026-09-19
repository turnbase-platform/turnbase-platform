package io.turnbase.platform.common;

import java.util.Locale;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * {@code game-asset-serving.md} §4: classifies a request's {@code Host} header as a game origin
 * (matching {@link GameFrontendProperties}'s template, literal or {@code {gameId}}-templated) or
 * not. "Not a game origin" is read as "the platform origin" per §4's table, which enumerates
 * exactly two buckets — this does not additionally require an exact match against {@link
 * PlatformOriginProperties}, so a Host the platform origin config does not literally name (as every
 * test harness's {@code 127.0.0.1:<port>} is) still reaches the platform API, while a game-origin
 * Host still cannot.
 *
 * <p>Matching compares hostnames only, ignoring any port on either side. {@code
 * local-environment.md} §3 (§ "Both origins are one listener") establishes that the platform and
 * games origins are always served on the identical port — the same process, same socket — so the
 * port carries no classifying information and comparing it would only make this brittle against the
 * port actually bound at runtime (e.g. an ephemeral test port).
 */
@Component
class GameOriginMatcher {

  private static final String PLACEHOLDER = "{gameId}";

  private final String gameHostTemplate;

  GameOriginMatcher(GameFrontendProperties gameFrontend) {
    // The placeholder search must run before case-folding: PLACEHOLDER is mixed-case and
    // hostOnly() lower-cases its result, which would otherwise hide "{gameId}" from indexOf.
    this.gameHostTemplate = hostOnly(gameFrontend.originTemplate());
  }

  boolean isGameHost(@Nullable String hostHeader) {
    if (hostHeader == null) {
      return false;
    }
    String host = hostOnly(hostHeader);
    int placeholder = gameHostTemplate.indexOf(PLACEHOLDER.toLowerCase(Locale.ROOT));
    if (placeholder < 0) {
      return gameHostTemplate.equals(host);
    }
    String prefix = gameHostTemplate.substring(0, placeholder);
    String suffix = gameHostTemplate.substring(placeholder + PLACEHOLDER.length());
    return host.length() > prefix.length() + suffix.length()
        && host.startsWith(prefix)
        && host.endsWith(suffix);
  }

  private static String hostOnly(String originOrHostHeader) {
    int schemeEnd = originOrHostHeader.indexOf("://");
    String hostPort =
        schemeEnd < 0 ? originOrHostHeader : originOrHostHeader.substring(schemeEnd + 3);
    int colon = hostPort.indexOf(':');
    String host = colon < 0 ? hostPort : hostPort.substring(0, colon);
    return host.toLowerCase(Locale.ROOT);
  }
}
