package io.turnbase.platform.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import io.turnbase.platform.common.GoldenJson;
import io.turnbase.platform.common.PageResponse;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

/** Golden payloads (IMP-M11-03): a catalog page as written on Boot 3.5 / Jackson 2. */
class CatalogPageWireFormatTest {

  @Test
  void aPageIsContentThenPaginationWithInstantsAsIsoStrings() throws Exception {
    var row =
        new CatalogRowResponse(
            UUID.fromString("11111111-2222-3333-4444-555555555555"),
            "Tic-Tac-Toe",
            "turnbase",
            "Noughts and crosses",
            2,
            2,
            false,
            true,
            "1.0.0",
            "OFFICIAL",
            Instant.parse("2026-10-10T12:30:45.123Z"),
            Instant.parse("2026-10-11T00:00:00Z"));
    var page = PageResponse.from(new PageImpl<>(List.of(row), PageRequest.of(0, 20), 1));

    assertThat(GoldenJson.mapper().writeValueAsString(page))
        .isEqualTo(
            "{\"content\":[{\"gameId\":\"11111111-2222-3333-4444-555555555555\","
                + "\"title\":\"Tic-Tac-Toe\",\"authorUsername\":\"turnbase\","
                + "\"shortDescription\":\"Noughts and crosses\",\"minPlayers\":2,\"maxPlayers\":2,"
                + "\"supportsAi\":false,\"supportsSpectators\":true,\"version\":\"1.0.0\","
                + "\"badge\":\"OFFICIAL\",\"createdAt\":\"2026-10-10T12:30:45.123Z\","
                + "\"updatedAt\":\"2026-10-11T00:00:00Z\"}],"
                + "\"pagination\":{\"page\":1,\"size\":20,\"totalElements\":1,\"totalPages\":1}}");
  }
}
