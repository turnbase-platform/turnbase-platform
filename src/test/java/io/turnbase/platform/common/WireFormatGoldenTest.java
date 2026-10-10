package io.turnbase.platform.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/** Golden payloads (IMP-M11-03): the bytes the platform wrote on Spring Boot 3.5 / Jackson 2. */
class WireFormatGoldenTest {

  @Test
  void aProblemDetailKeepsItsRfc9457MembersAndExtensions() throws Exception {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "bad input");
    problem.setType(java.net.URI.create("https://turnbase.io/problems/validation-failed"));
    problem.setTitle("Validation failed");
    problem.setInstance(java.net.URI.create("/api/v1/profile"));
    problem.setProperty("correlationId", "c-1");
    problem.setProperty("errors", List.of(new CheckError("rule", "explained")));

    assertThat(GoldenJson.mapper().writeValueAsString(problem))
        .isEqualTo(
            "{\"type\":\"https://turnbase.io/problems/validation-failed\","
                + "\"title\":\"Validation failed\",\"status\":400,\"detail\":\"bad input\","
                + "\"instance\":\"/api/v1/profile\",\"correlationId\":\"c-1\","
                + "\"errors\":[{\"check\":\"rule\",\"message\":\"explained\"}]}");
  }

  @Test
  void aStompRoomEventIsTheEnvelopeWithTheNativePayload() {
    UUID id = UUID.fromString("11111111-2222-3333-4444-555555555555");
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("playerId", id);
    payload.put("joinOrder", 4L);
    payload.put("teamId", null);

    GoldenJson.assertSameJson(
        GoldenJson.stompBody(Map.of("type", "PLAYER_JOINED", "version", 3L, "payload", payload)),
        "{\"type\":\"PLAYER_JOINED\",\"version\":3,\"payload\":{"
            + "\"playerId\":\"11111111-2222-3333-4444-555555555555\","
            + "\"joinOrder\":4,\"teamId\":null}}");
  }
}
