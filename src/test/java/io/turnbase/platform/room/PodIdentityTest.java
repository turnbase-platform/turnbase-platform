package io.turnbase.platform.room;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PodIdentityTest {

  @Test
  void idCarriesTheDashedPodAddressForTheFrontendProxy() {
    assertThat(new PodIdentity("tb-platform-7c57-abcde", "10.1.6.38").id())
        .matches("tb-platform-7c57-abcde@10-1-6-38:[0-9a-f-]{36}");
  }

  @Test
  void idIsNameAndUuidWithoutAnAddress() {
    assertThat(new PodIdentity("platform-1", null).id()).matches("platform-1:[0-9a-f-]{36}");
    assertThat(new PodIdentity(" ", " ").id()).matches("platform:[0-9a-f-]{36}");
  }

  @Test
  void everyIncarnationIsDistinct() {
    assertThat(new PodIdentity("p", "10.0.0.1").id())
        .isNotEqualTo(new PodIdentity("p", "10.0.0.1").id());
  }
}
