package io.turnbase.platform.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

class DistinctRegistrableDomainsCheckTest {

  @Test
  void passesForTheLocalDefaults() {
    var check =
        new DistinctRegistrableDomainsCheck(
            new PlatformOriginProperties("http://turnbase.localhost:8080"),
            new GameFrontendProperties("http://{gameId}.games.localhost:8080"));

    assertThatCode(() -> check.run(new DefaultApplicationArguments())).doesNotThrowAnyException();
  }

  @Test
  void failsWhenTheGameFrontendIsASubdomainOfThePlatform() {
    var check =
        new DistinctRegistrableDomainsCheck(
            new PlatformOriginProperties("http://turnbase.localhost:8080"),
            new GameFrontendProperties("http://{gameId}.games.turnbase.localhost:8080"));

    assertThatThrownBy(() -> check.run(new DefaultApplicationArguments()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("ADR-0007");
  }

  @Test
  void registrableDomainKeepsTheLastTwoLabels() {
    assertThat(DistinctRegistrableDomainsCheck.registrableDomain("turnbase.localhost"))
        .isEqualTo("turnbase.localhost");
    assertThat(DistinctRegistrableDomainsCheck.registrableDomain("game.games.localhost"))
        .isEqualTo("games.localhost");
    assertThat(DistinctRegistrableDomainsCheck.registrableDomain("localhost"))
        .isEqualTo("localhost");
  }
}
