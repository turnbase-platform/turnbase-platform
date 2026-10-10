package io.turnbase.platform.account;

import static org.assertj.core.api.Assertions.assertThat;

import io.turnbase.platform.common.GoldenJson;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Golden payloads (IMP-M11-03): the profile body as written on Boot 3.5 / Jackson 2. */
class ProfileWireFormatTest {

  @Test
  void aRegisteredProfileOmitsNullMembersAndKeepsDeclarationOrder() throws Exception {
    var profile =
        ProfileResponse.registered(
            UUID.fromString("11111111-2222-3333-4444-555555555555"),
            "alice",
            false,
            Set.of("gameHistory"),
            "FRIEND");

    assertThat(GoldenJson.mapper().writeValueAsString(profile))
        .isEqualTo(
            "{\"username\":\"alice\",\"userId\":\"11111111-2222-3333-4444-555555555555\","
                + "\"kind\":\"REGISTERED\",\"profilePrivate\":false,"
                + "\"visibleSections\":[\"gameHistory\"],\"relationshipState\":\"FRIEND\"}");
  }

  @Test
  void aDeletedPlaceholderIsTwoMembers() throws Exception {
    assertThat(GoldenJson.mapper().writeValueAsString(ProfileResponse.deletedPlaceholder("bob")))
        .isEqualTo("{\"username\":\"bob\",\"deleted\":true}");
  }
}
