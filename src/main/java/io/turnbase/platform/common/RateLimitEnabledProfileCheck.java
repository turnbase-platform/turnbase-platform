package io.turnbase.platform.common;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * local-environment.md §7.6: {@code turnbase.rate-limit.enabled=false}, or any per-mechanism switch
 * set to {@code false}, is honoured only under the {@code test}, {@code e2e} or {@code loadtest}
 * profile — anywhere else, disabling it silently would remove NFR-RATE protection with nothing else
 * noticing.
 *
 * <p>The switches are read from the {@link Environment} by key rather than from the typed records:
 * the Tier 6 record lives in {@code websocket}, which {@code common} must not depend on (ADR-0014).
 */
@Component
public class RateLimitEnabledProfileCheck implements ApplicationRunner {

  private static final String MASTER_KEY = "turnbase.rate-limit.enabled";

  /** Every per-mechanism switch; a test keeps this in step with both {@code Switches} records. */
  static final List<String> SWITCH_KEYS =
      List.of(
          "turnbase.rate-limit.switches.auth-lockout",
          "turnbase.rate-limit.switches.api",
          "turnbase.rate-limit.switches.upload",
          "turnbase.rate-limit.switches.public",
          "turnbase.rate-limit.switches.guest-minting",
          "turnbase.rate-limit.switches.registration-minting",
          "turnbase.rate-limit.ws.switches.connection",
          "turnbase.rate-limit.ws.switches.snapshot",
          "turnbase.rate-limit.ws.switches.action",
          "turnbase.rate-limit.ws.switches.chat",
          "turnbase.rate-limit.ws.switches.command",
          "turnbase.rate-limit.ws.switches.subscribe",
          "turnbase.rate-limit.ws.switches.violation-lockout",
          "turnbase.rate-limit.ws.switches.max-connections-per-user");

  private final RateLimitProperties rateLimit;
  private final Environment environment;

  public RateLimitEnabledProfileCheck(RateLimitProperties rateLimit, Environment environment) {
    this.rateLimit = rateLimit;
    this.environment = environment;
  }

  @Override
  public void run(ApplicationArguments args) {
    List<String> disabled = new ArrayList<>();
    if (!rateLimit.enabled()) {
      disabled.add(MASTER_KEY);
    }
    for (String key : SWITCH_KEYS) {
      if (!environment.getProperty(key, Boolean.class, true)) {
        disabled.add(key);
      }
    }
    if (!disabled.isEmpty() && !environment.matchesProfiles("test", "e2e", "loadtest")) {
      throw new IllegalStateException(
          "rate limiting is disabled ("
              + String.join(", ", disabled)
              + ") but is only honoured under the test, e2e or loadtest profile"
              + " (local-environment.md §7.6); active profiles: "
              + String.join(",", environment.getActiveProfiles()));
    }
  }
}
