package io.turnbase.platform.upload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.turnbase.platform.common.CheckError;
import io.turnbase.platform.common.GoldenJson;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Golden payloads (IMP-M11-03): the {@code jsonb} validation columns as written on Boot 3.5. */
class ValidationChecksWireFormatTest {

  private final GameVersionRepository repository =
      new GameVersionRepository(mock(JdbcClient.class), GoldenJson.mapper());

  @Test
  void checksAreStoredAsAnArrayOfCheckAndMessage() throws Exception {
    Method jsonOf = GameVersionRepository.class.getDeclaredMethod("jsonOf", List.class);
    jsonOf.setAccessible(true);

    assertThat(jsonOf.invoke(repository, List.of(new CheckError("manifest", "no \"id\""))))
        .isEqualTo("[{\"check\":\"manifest\",\"message\":\"no \\\"id\\\"\"}]");
  }

  @Test
  void checksWrittenByTheOldBuildAreStillRead() throws Exception {
    Method checksOf = GameVersionRepository.class.getDeclaredMethod("checksOf", String.class);
    checksOf.setAccessible(true);

    assertThat(
            checksOf.invoke(repository, "[{\"check\":\"manifest\",\"message\":\"no \\\"id\\\"\"}]"))
        .isEqualTo(List.of(new CheckError("manifest", "no \"id\"")));
  }
}
