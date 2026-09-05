package io.turnbase.platform.common;

import java.net.URI;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * ADR-0007 / local-environment.md §7.7: the platform origin and the game-frontend origin template
 * must resolve to different registrable domains, or the {@code SameSite} CSRF defense {@code
 * rest-conventions.md} relies on stops holding — nothing else would fail, which is why this refuses
 * to boot rather than leaving it to be discovered as a security gap.
 */
@Component
public class DistinctRegistrableDomainsCheck implements ApplicationRunner {

  private final PlatformOriginProperties platformOrigin;
  private final GameFrontendProperties gameFrontend;

  public DistinctRegistrableDomainsCheck(
      PlatformOriginProperties platformOrigin, GameFrontendProperties gameFrontend) {
    this.platformOrigin = platformOrigin;
    this.gameFrontend = gameFrontend;
  }

  @Override
  public void run(ApplicationArguments args) {
    String platformDomain = registrableDomain(host(platformOrigin.origin()));
    String gameFrontendDomain =
        registrableDomain(host(gameFrontend.originTemplate().replace("{gameId}", "game")));
    if (platformDomain.equals(gameFrontendDomain)) {
      throw new IllegalStateException(
          "turnbase.platform.origin ("
              + platformOrigin.origin()
              + ") and turnbase.game-frontend.origin-template ("
              + gameFrontend.originTemplate()
              + ") resolve to the same registrable domain ("
              + platformDomain
              + "). ADR-0007 requires them to differ.");
    }
  }

  private static String host(String uri) {
    String host = URI.create(uri).getHost();
    if (host == null) {
      throw new IllegalStateException("'" + uri + "' has no host component");
    }
    return host;
  }

  /**
   * The Public Suffix List's unknown-suffix rule (ADR-0007), simplified to "last two labels" —
   * exact for {@code *.localhost} locally and for a dedicated second domain in production (ADR-0007
   * names {@code googleusercontent.com}/{@code github.io}-style hosting, never a multi-label public
   * suffix like {@code co.uk}). A real Public Suffix List lookup is production's escalation if that
   * ever changes.
   */
  static String registrableDomain(String host) {
    String[] labels = host.split("\\.");
    if (labels.length <= 2) {
      return host;
    }
    return labels[labels.length - 2] + "." + labels[labels.length - 1];
  }
}
