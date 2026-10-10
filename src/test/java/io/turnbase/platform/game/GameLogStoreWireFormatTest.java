package io.turnbase.platform.game;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.turnbase.platform.common.GoldenJson;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

/** Golden payloads (IMP-M11-03): the Redis-stored game-log value as written on Boot 3.5. */
class GameLogStoreWireFormatTest {

  private final StringRedisTemplate redis = mock(StringRedisTemplate.class, RETURNS_DEEP_STUBS);
  private final GameLogStore store = new GameLogStore(redis, GoldenJson.mapper());
  private final UUID session = UUID.fromString("11111111-2222-3333-4444-555555555555");

  @Test
  void anEntryIsStoredAsAFlatObjectInDeclarationOrder() {
    store.append(session, new GameLogStore.Entry(7L, 2, "{\"cell\":4}"));

    verify(redis.opsForList())
        .rightPush(
            "gamelog:11111111-2222-3333-4444-555555555555",
            "{\"sequence\":7,\"index\":2,\"entry\":\"{\\\"cell\\\":4}\"}");
  }

  @Test
  void anEntryWrittenByTheOldBuildIsStillRead() {
    when(redis
            .opsForList()
            .range(
                anyString(),
                org.mockito.ArgumentMatchers.eq(0L),
                org.mockito.ArgumentMatchers.eq(-1L)))
        .thenReturn(List.of("{\"sequence\":7,\"index\":2,\"entry\":\"{\\\"cell\\\":4}\"}"));

    assertThat(store.read(session)).containsExactly(new GameLogStore.Entry(7L, 2, "{\"cell\":4}"));
  }
}
