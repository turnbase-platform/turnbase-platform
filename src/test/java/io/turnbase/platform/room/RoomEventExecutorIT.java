package io.turnbase.platform.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.turnbase.platform.PostgresIntegrationTest;
import io.turnbase.platform.SharedApplication;
import io.turnbase.platform.UsesSharedApplication;
import io.turnbase.platform.common.RoomUnavailableRetryException;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@code IMP-M2-04}'s own Done-when, at the mechanism level (the end-to-end broadcast is {@link
 * RoomEventFanoutIT}): the version is committed before it is ever handed to a mutation's caller,
 * assignment comes from {@link RoomOwnershipService#nextVersion} rather than a fresh read, a
 * no-event mutation never touches {@code room.version}, and a fenced commit failure evicts the room
 * rather than leaving this instance's dirty in-memory state in play.
 */
@UsesSharedApplication
class RoomEventExecutorIT extends PostgresIntegrationTest {

  private @Nullable ConfigurableApplicationContext context;

  @Test
  void aMutationWithAnEventAssignsAndCommitsTheNextVersion() {
    context = boot();
    RoomEventExecutor executor = context.getBean(RoomEventExecutor.class);
    RoomOwnershipService ownership = context.getBean(RoomOwnershipService.class);
    UUID roomId = insertRoom(context);
    ownership.claim(roomId);

    String result =
        executor.execute(
            roomId,
            () ->
                new RoomEventExecutor.MutationOutcome<>(
                    "applied", new RoomEventExecutor.RoomEvent("TEST_EVENT", Map.of("k", "v"))));

    assertThat(result).isEqualTo("applied");
    assertThat(roomVersion(context, roomId)).isEqualTo(1L);

    // A second mutation assigns from the counter, not from a fresh max() of the database.
    executor.execute(
        roomId,
        () ->
            new RoomEventExecutor.MutationOutcome<>(
                "applied again", new RoomEventExecutor.RoomEvent("TEST_EVENT", Map.of())));
    assertThat(roomVersion(context, roomId)).isEqualTo(2L);
  }

  /** IMP-M7-10 / MR-06: a committed room event is one INFO line with roomId, userId, reason. */
  @Test
  void aCommittedRoomEventIsLoggedWithRoomUserAndReason() {
    context = boot();
    RoomEventExecutor executor = context.getBean(RoomEventExecutor.class);
    RoomOwnershipService ownership = context.getBean(RoomOwnershipService.class);
    UUID roomId = insertRoom(context);
    UUID userId = UUID.randomUUID();
    ownership.claim(roomId);
    Logger logger = (Logger) LoggerFactory.getLogger(RoomEventExecutor.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      executor.execute(
          roomId,
          () ->
              new RoomEventExecutor.MutationOutcome<>(
                  "x",
                  new RoomEventExecutor.RoomEvent(
                      "PLAYER_LOST",
                      new RoomRepository.PlayerLostPayload(userId, null, "GRACE_PERIOD_EXPIRED"))));
    } finally {
      logger.detachAppender(appender);
    }

    assertThat(appender.list)
        .extracting(ILoggingEvent::getFormattedMessage)
        .containsExactly(
            "Room event event=PLAYER_LOST roomId="
                + roomId
                + " userId="
                + userId
                + " reason=GRACE_PERIOD_EXPIRED roomVersion=1");
  }

  @Test
  void aMutationWithNoEventNeverBumpsTheVersion() {
    context = boot();
    RoomEventExecutor executor = context.getBean(RoomEventExecutor.class);
    RoomOwnershipService ownership = context.getBean(RoomOwnershipService.class);
    UUID roomId = insertRoom(context);
    ownership.claim(roomId);

    String result =
        executor.execute(roomId, () -> new RoomEventExecutor.MutationOutcome<>("no-op", null));

    assertThat(result).isEqualTo("no-op");
    assertThat(roomVersion(context, roomId)).isEqualTo(0L);
  }

  @Test
  void aFencedCommitFailureEvictsTheRoomRatherThanRetryingInPlace() {
    context = boot();
    RoomEventExecutor executor = context.getBean(RoomEventExecutor.class);
    RoomOwnershipService ownership = context.getBean(RoomOwnershipService.class);
    UUID roomId = insertRoom(context);
    ownership.claim(roomId);
    // Simulate a stale owner: another tenure's epoch is now recorded, as adoption would leave it,
    // without this instance's own claim having re-run to notice.
    context
        .getBean(JdbcClient.class)
        .sql("update room set owner_epoch = owner_epoch + 1 where room_id = :roomId")
        .param("roomId", roomId)
        .update();

    assertThatThrownBy(
            () ->
                executor.execute(
                    roomId,
                    () ->
                        new RoomEventExecutor.MutationOutcome<>(
                            "applied", new RoomEventExecutor.RoomEvent("TEST_EVENT", Map.of()))))
        .isInstanceOf(RoomUnavailableRetryException.class);

    assertThat(ownership.epochOf(roomId)).isEmpty();
  }

  /**
   * IMP-M16-07: {@code epochOf} passes under the lock, an eviction then runs (here, inside the
   * mutation, standing in for the re-check task on its own thread), and {@code nextVersion} finds
   * the room gone. That must be the retry directive, not an {@code IllegalStateException}.
   */
  @Test
  void anEvictionBetweenTheEpochLookupAndTheVersionAssignmentIsARetryNotAServerError() {
    context = boot();
    RoomEventExecutor executor = context.getBean(RoomEventExecutor.class);
    RoomOwnershipService ownership = context.getBean(RoomOwnershipService.class);
    UUID roomId = insertRoom(context);
    ownership.claim(roomId);

    assertThatThrownBy(
            () ->
                executor.execute(
                    roomId,
                    () -> {
                      ownership.evictAfterFailedCas(roomId);
                      return new RoomEventExecutor.MutationOutcome<>(
                          "applied", new RoomEventExecutor.RoomEvent("TEST_EVENT", Map.of()));
                    }))
        .isInstanceOf(RoomUnavailableRetryException.class);

    assertThat(roomVersion(context, roomId)).isEqualTo(0L);
  }

  @Test
  void executingAgainstARoomThisInstanceHasNotClaimedFailsWithoutMutating() {
    context = boot();
    RoomEventExecutor executor = context.getBean(RoomEventExecutor.class);
    UUID roomId = insertRoom(context);

    assertThatThrownBy(
            () ->
                executor.execute(
                    roomId,
                    () ->
                        new RoomEventExecutor.MutationOutcome<>(
                            "applied", new RoomEventExecutor.RoomEvent("TEST_EVENT", Map.of()))))
        .isInstanceOf(RoomUnavailableRetryException.class);

    assertThat(roomVersion(context, roomId)).isEqualTo(0L);
  }

  private static ConfigurableApplicationContext boot() {
    return SharedApplication.run(
        "--server.port=0",
        "--management.server.port=0",
        "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
        "--spring.datasource.username=" + POSTGRES.getUsername(),
        "--spring.datasource.password=" + POSTGRES.getPassword(),
        "--turnbase.artifact-store.endpoint=" + MINIO.getS3URL(),
        "--turnbase.artifact-store.bucket=" + ARTIFACT_BUCKET,
        "--turnbase.artifact-store.access-key=" + MINIO.getUserName(),
        "--turnbase.artifact-store.secret-key=" + MINIO.getPassword(),
        "--turnbase.admin.username=admin",
        "--turnbase.admin.password=admin",
        "--spring.data.redis.url=" + redisUrl(),
        "--turnbase.room.lease.renew-interval=1h");
  }

  private static UUID insertRoom(ConfigurableApplicationContext context) {
    UUID gameId = UUID.randomUUID();
    UUID roomId = UUID.randomUUID();
    JdbcClient jdbcClient = context.getBean(JdbcClient.class);
    jdbcClient
        .sql(
            "INSERT INTO game (game_id, title, short_description, badge, owner_user_id)"
                + " VALUES (:gameId, 'Test Game', 'A game for testing.', 'OFFICIAL', NULL)")
        .param("gameId", gameId)
        .update();
    jdbcClient
        .sql(
            "INSERT INTO room (room_id, game_id, game_version, visibility, state)"
                + " VALUES (:roomId, :gameId, '1.0.0', 'PUBLIC', 'LOBBY')")
        .param("roomId", roomId)
        .param("gameId", gameId)
        .update();
    return roomId;
  }

  private static long roomVersion(ConfigurableApplicationContext context, UUID roomId) {
    Number version =
        context
            .getBean(JdbcClient.class)
            .sql("select version from room where room_id = :roomId")
            .param("roomId", roomId)
            .query(Number.class)
            .single();
    return version.longValue();
  }
}
