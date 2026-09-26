package io.turnbase.platform.seed;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * {@code local-environment.md} §6 — {@code local} and {@code e2e} only, never a migration ({@code
 * schema-migrations.md} §7).
 */
@Component
@Profile({"local", "e2e"})
class SeedRunner implements ApplicationRunner {

  private final SocialSeed socialSeed;
  private final GameBundleSeeder gameBundleSeeder;

  SeedRunner(SocialSeed socialSeed, GameBundleSeeder gameBundleSeeder) {
    this.socialSeed = socialSeed;
    this.gameBundleSeeder = gameBundleSeeder;
  }

  @Override
  public void run(ApplicationArguments args) throws Exception {
    socialSeed.apply();
    gameBundleSeeder.apply();
  }
}
