package io.turnbase.platform.common;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * local-environment.md §7.6: {@code turnbase.rate-limit.enabled=false} is honoured only under the
 * {@code test} or {@code e2e} profile — anywhere else, disabling it silently would remove NFR-RATE
 * protection with nothing else noticing.
 */
@Component
public class RateLimitEnabledProfileCheck implements ApplicationRunner {

  private final RateLimitProperties rateLimit;
  private final Environment environment;

  public RateLimitEnabledProfileCheck(RateLimitProperties rateLimit, Environment environment) {
    this.rateLimit = rateLimit;
    this.environment = environment;
  }

  @Override
  public void run(ApplicationArguments args) {
    if (!rateLimit.enabled() && !environment.matchesProfiles("test", "e2e")) {
      throw new IllegalStateException(
          "turnbase.rate-limit.enabled=false is only honoured under the test or e2e profile"
              + " (local-environment.md §7.6); active profiles: "
              + String.join(",", environment.getActiveProfiles()));
    }
  }
}
