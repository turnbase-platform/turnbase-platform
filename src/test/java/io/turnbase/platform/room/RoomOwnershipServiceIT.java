package io.turnbase.platform.room;

import static org.assertj.core.api.Assertions.assertThat;

import io.turnbase.platform.PostgresIntegrationTest;
import io.turnbase.platform.TurnBaseApplication;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.LivenessState;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code IMP-M2-03}'s own Done-when: the claim script is atomic and is also the failure detector,
 * the four layers of {@code room-lifecycle.md} §1 are all present, self-eviction triggers on lease
 * *lapse* rather than a mere renewal failure, and the room version is never used as the fence.
 *
 * <p>Two full {@link TurnBaseApplication} contexts stand in for two Platform Monolith instances —
 * {@link PodIdentity} is a random-per-boot singleton, so two contexts naturally get two distinct
 * {@code podId}s sharing the same Postgres/Redis, exactly as two real pods would.
 */
class RoomOwnershipServiceIT extends PostgresIntegrationTest {

  private @Nullable ConfigurableApplicationContext instanceA;
  private @Nullable ConfigurableApplicationContext instanceB;

  @AfterEach
  void stopInstances() {
    if (instanceA != null) {
      instanceA.close();
    }
    if (instanceB != null) {
      instanceB.close();
    }
  }

  @Test
  void firstClaimOnAFreshRoomAdoptsAndStampsTheRoomRow() {
    instanceA = boot();
    RoomOwnershipService ownershipA = instanceA.getBean(RoomOwnershipService.class);
    PodIdentity podA = instanceA.getBean(PodIdentity.class);
    UUID roomId = insertRoom(instanceA);

    RoomOwnershipService.ClaimOutcome outcome = ownershipA.claim(roomId);

    assertThat(outcome).isInstanceOf(RoomOwnershipService.ClaimOutcome.Adopted.class);
    var adopted = (RoomOwnershipService.ClaimOutcome.Adopted) outcome;
    assertThat(adopted.epoch()).isEqualTo(1L);
    assertThat(adopted.version()).isEqualTo(0L);

    Map<String, Object> row = roomRow(instanceA, roomId);
    assertThat(row.get("owner_instance_id")).isEqualTo(podA.id());
    assertThat(((Number) Objects.requireNonNull(row.get("owner_epoch"))).longValue()).isEqualTo(1L);
    assertThat(row.get("ownerless_since")).isNull();
    assertThat(row.get("migration_window_expires_at")).isNotNull();

    assertThat(ownershipA.epochOf(roomId)).contains(1L);
  }

  @Test
  void reclaimingAnAlreadyOwnedRoomIsIdempotentAndDoesNotBumpTheEpoch() {
    instanceA = boot();
    RoomOwnershipService ownershipA = instanceA.getBean(RoomOwnershipService.class);
    UUID roomId = insertRoom(instanceA);

    ownershipA.claim(roomId);
    RoomOwnershipService.ClaimOutcome second = ownershipA.claim(roomId);

    assertThat(second).isInstanceOf(RoomOwnershipService.ClaimOutcome.AlreadyMine.class);
    assertThat(((RoomOwnershipService.ClaimOutcome.AlreadyMine) second).epoch()).isEqualTo(1L);
  }

  @Test
  void aSecondInstanceIsRedirectedWhileTheOwnerIsLive() {
    instanceA = boot();
    instanceB = boot();
    RoomOwnershipService ownershipA = instanceA.getBean(RoomOwnershipService.class);
    RoomOwnershipService ownershipB = instanceB.getBean(RoomOwnershipService.class);
    PodIdentity podA = instanceA.getBean(PodIdentity.class);
    UUID roomId = insertRoom(instanceA);

    ownershipA.claim(roomId);
    RoomOwnershipService.ClaimOutcome outcome = ownershipB.claim(roomId);

    assertThat(outcome).isInstanceOf(RoomOwnershipService.ClaimOutcome.Redirect.class);
    assertThat(((RoomOwnershipService.ClaimOutcome.Redirect) outcome).ownerPodId())
        .isEqualTo(podA.id());
    // A redirect never touches the room row.
    Map<String, Object> row = roomRow(instanceA, roomId);
    assertThat(row.get("owner_instance_id")).isEqualTo(podA.id());
    assertThat(((Number) Objects.requireNonNull(row.get("owner_epoch"))).longValue()).isEqualTo(1L);
  }

  @Test
  void claimWithRetryGivesUpAndReturnsRedirectWhenTheIncumbentStaysLive() {
    instanceA = boot();
    instanceB = boot();
    RoomOwnershipService ownershipA = instanceA.getBean(RoomOwnershipService.class);
    RoomOwnershipService ownershipB = instanceB.getBean(RoomOwnershipService.class);
    PodIdentity podA = instanceA.getBean(PodIdentity.class);
    UUID roomId = insertRoom(instanceA);
    ownershipA.claim(roomId);

    RoomOwnershipService.ClaimOutcome outcome = ownershipB.claimWithRetry(roomId);

    assertThat(outcome).isInstanceOf(RoomOwnershipService.ClaimOutcome.Redirect.class);
    assertThat(((RoomOwnershipService.ClaimOutcome.Redirect) outcome).ownerPodId())
        .isEqualTo(podA.id());
  }

  @Test
  void claimWithRetrySucceedsImmediatelyWhenTheRoomIsAlreadyOwnedByThisInstance() {
    instanceA = boot();
    RoomOwnershipService ownershipA = instanceA.getBean(RoomOwnershipService.class);
    UUID roomId = insertRoom(instanceA);
    ownershipA.claim(roomId);

    RoomOwnershipService.ClaimOutcome outcome = ownershipA.claimWithRetry(roomId);

    assertThat(outcome).isInstanceOf(RoomOwnershipService.ClaimOutcome.AlreadyMine.class);
  }

  @Test
  void aSecondInstanceAdoptsOnceTheIncumbentsLeaseHasLapsed() {
    instanceA = boot();
    instanceB = boot();
    RoomOwnershipService ownershipA = instanceA.getBean(RoomOwnershipService.class);
    RoomOwnershipService ownershipB = instanceB.getBean(RoomOwnershipService.class);
    PodIdentity podB = instanceB.getBean(PodIdentity.class);
    UUID roomId = insertRoom(instanceA);
    ownershipA.claim(roomId);

    // Simulate A's liveness lease lapsing (crash, flush, long pause) without waiting out the TTL.
    deleteLivenessKey(instanceA, instanceA.getBean(PodIdentity.class).id());

    RoomOwnershipService.ClaimOutcome outcome = ownershipB.claim(roomId);

    assertThat(outcome).isInstanceOf(RoomOwnershipService.ClaimOutcome.Adopted.class);
    var adopted = (RoomOwnershipService.ClaimOutcome.Adopted) outcome;
    assertThat(adopted.epoch()).isEqualTo(2L); // monotonic — never resets
    Map<String, Object> row = roomRow(instanceA, roomId);
    assertThat(row.get("owner_instance_id")).isEqualTo(podB.id());
    assertThat(((Number) Objects.requireNonNull(row.get("owner_epoch"))).longValue()).isEqualTo(2L);
  }

  @Test
  void selfEvictionOnLeaseLapseDropsEveryOwnedRoomAndNeverRecreatesTheKey() {
    instanceA = boot();
    RoomOwnershipService ownershipA = instanceA.getBean(RoomOwnershipService.class);
    PodIdentity podA = instanceA.getBean(PodIdentity.class);
    UUID roomId = insertRoom(instanceA);
    ownershipA.claim(roomId);
    assertThat(ownershipA.epochOf(roomId)).isPresent();

    deleteLivenessKey(instanceA, podA.id());
    ownershipA.renewAndRecheck(); // the scheduled tick, run manually and deterministically

    assertThat(ownershipA.epochOf(roomId)).isEmpty();
    // Never recreates the key — a later renewal attempt must not resurrect it.
    ownershipA.renewAndRecheck();
    StringRedisTemplate redis = instanceA.getBean(StringRedisTemplate.class);
    assertThat(redis.opsForValue().get("pod:" + podA.id() + ":alive")).isNull();
  }

  /** IMP-M16-07: §1.5 layer 3 stays fatal, realised as a restart by Kubernetes. */
  @Test
  void aSelfEvictionPublishesBrokenLiveness() {
    instanceA = boot();
    RoomOwnershipService ownershipA = instanceA.getBean(RoomOwnershipService.class);
    ApplicationAvailability availability = instanceA.getBean(ApplicationAvailability.class);
    assertThat(availability.getLivenessState()).isEqualTo(LivenessState.CORRECT);
    UUID roomId = insertRoom(instanceA);
    ownershipA.claim(roomId);

    deleteLivenessKey(instanceA, instanceA.getBean(PodIdentity.class).id());
    ownershipA.renewLease();

    assertThat(availability.getLivenessState()).isEqualTo(LivenessState.BROKEN);
    assertThat(ownershipA.epochOf(roomId)).isEmpty();
  }

  @Test
  void aRoomThatReachesClosedLeavesTheOwnedSetOnceItsTransactionCommits() {
    instanceA = boot();
    RoomOwnershipService ownershipA = instanceA.getBean(RoomOwnershipService.class);
    RoomRepository repository = instanceA.getBean(RoomRepository.class);
    TransactionTemplate transaction =
        new TransactionTemplate(
            instanceA.getBean(org.springframework.transaction.PlatformTransactionManager.class));
    UUID rolledBack = insertRoom(instanceA);
    UUID committed = insertRoom(instanceA);
    UUID bare = insertRoom(instanceA);
    UUID stillOpen = insertRoom(instanceA);
    for (UUID roomId : new UUID[] {rolledBack, committed, bare, stillOpen}) {
      ownershipA.claim(roomId);
    }

    transaction.executeWithoutResult(
        status -> {
          repository.detachSession(rolledBack, null, "NO_PLAYERS_REMAINING", "CLOSED");
          assertThat(ownershipA.epochOf(rolledBack)).isPresent(); // not before the commit
          status.setRollbackOnly();
        });
    assertThat(ownershipA.epochOf(rolledBack)).isPresent();

    transaction.executeWithoutResult(
        status -> repository.detachSession(committed, null, "NO_PLAYERS_REMAINING", "CLOSED"));
    assertThat(ownershipA.epochOf(committed)).isEmpty();

    // Without a transaction (unit tests call detachSession bare) the callback runs immediately.
    repository.detachSession(bare, null, "NO_PLAYERS_REMAINING", "CLOSED");
    assertThat(ownershipA.epochOf(bare)).isEmpty();

    assertThat(ownershipA.epochOf(stillOpen)).isPresent();
  }

  @Test
  void aClosedRoomsOwnershipKeysAreDeletedOnceItsTransactionCommits_IMP_M16_08() {
    instanceA = boot();
    RoomOwnershipService ownershipA = instanceA.getBean(RoomOwnershipService.class);
    RoomRepository repository = instanceA.getBean(RoomRepository.class);
    StringRedisTemplate redis = instanceA.getBean(StringRedisTemplate.class);
    TransactionTemplate transaction =
        new TransactionTemplate(
            instanceA.getBean(org.springframework.transaction.PlatformTransactionManager.class));
    UUID rolledBack = insertRoom(instanceA);
    UUID committed = insertRoom(instanceA);
    UUID bare = insertRoom(instanceA);
    UUID stillOpen = insertRoom(instanceA);
    for (UUID roomId : new UUID[] {rolledBack, committed, bare, stillOpen}) {
      ownershipA.claim(roomId);
    }
    resetRedisStats(redis);

    transaction.executeWithoutResult(
        status -> {
          repository.detachSession(rolledBack, null, "NO_PLAYERS_REMAINING", "CLOSED");
          status.setRollbackOnly();
        });
    assertThat(redis.hasKey("room:" + rolledBack + ":pod")).isTrue();
    assertThat(redis.hasKey("room:" + rolledBack + ":epoch")).isTrue();

    transaction.executeWithoutResult(
        status -> repository.detachSession(committed, null, "NO_PLAYERS_REMAINING", "CLOSED"));
    repository.detachSession(bare, null, "NO_PLAYERS_REMAINING", "CLOSED");
    for (UUID closed : new UUID[] {committed, bare}) {
      assertThat(redis.hasKey("room:" + closed + ":pod")).isFalse();
      assertThat(redis.hasKey("room:" + closed + ":epoch")).isFalse();
    }
    assertThat(redis.hasKey("room:" + stillOpen + ":pod")).isTrue();
    assertThat(redis.hasKey("room:" + stillOpen + ":epoch")).isTrue();
    assertThat(redisCommandCalls(redis, "keys")).isZero();
  }

  @Test
  void closingARoomLeavesAClaimKeyThatNamesAnotherInstanceAlone_IMP_M16_08() {
    instanceA = boot();
    RoomOwnershipService ownershipA = instanceA.getBean(RoomOwnershipService.class);
    RoomRepository repository = instanceA.getBean(RoomRepository.class);
    StringRedisTemplate redis = instanceA.getBean(StringRedisTemplate.class);
    UUID roomId = insertRoom(instanceA);
    ownershipA.claim(roomId);
    redis.opsForValue().set("room:" + roomId + ":pod", "someone-else");

    repository.detachSession(roomId, null, "NO_PLAYERS_REMAINING", "CLOSED");

    assertThat(redis.opsForValue().get("room:" + roomId + ":pod")).isEqualTo("someone-else");
    assertThat(redis.hasKey("room:" + roomId + ":epoch")).isTrue();
  }

  @Test
  void leaseRenewalDoesNotWalkTheOwnedRooms() {
    instanceA = boot();
    RoomOwnershipService ownershipA = instanceA.getBean(RoomOwnershipService.class);
    StringRedisTemplate redis = instanceA.getBean(StringRedisTemplate.class);
    UUID roomId = insertRoom(instanceA);
    ownershipA.claim(roomId);
    // A room whose key names someone else: only the re-check may notice, never the renewal.
    redis.opsForValue().set("room:" + roomId + ":pod", "some-other-pod");

    ownershipA.renewLease();

    assertThat(ownershipA.epochOf(roomId)).isPresent();
    ownershipA.recheckOwnership();
    assertThat(ownershipA.epochOf(roomId)).isEmpty();
  }

  @Test
  void aRecheckPassOver20000OwnedRoomsTakesUnderTwoSeconds() {
    instanceA = boot();
    RoomOwnershipService ownershipA = instanceA.getBean(RoomOwnershipService.class);
    StringRedisTemplate redis = instanceA.getBean(StringRedisTemplate.class);
    String podId = instanceA.getBean(PodIdentity.class).id();
    Map<String, String> keys = new HashMap<>();
    UUID stolen = UUID.randomUUID();
    UUID missing = UUID.randomUUID();
    for (int i = 0; i < 20_000; i++) {
      UUID roomId = UUID.randomUUID();
      ownershipA.registerOwned(roomId, 1, 0);
      keys.put("room:" + roomId + ":pod", podId);
      if (keys.size() == 1_000) {
        redis.opsForValue().multiSet(keys);
        keys.clear();
      }
    }
    ownershipA.registerOwned(stolen, 1, 0);
    redis.opsForValue().set("room:" + stolen + ":pod", "some-other-pod");
    ownershipA.registerOwned(missing, 1, 0); // its key was flushed

    long startNanos = System.nanoTime();
    ownershipA.recheckOwnership();
    Duration took = Duration.ofNanos(System.nanoTime() - startNanos);

    assertThat(took).isLessThan(Duration.ofSeconds(2));
    assertThat(ownershipA.epochOf(stolen)).isEmpty();
    assertThat(ownershipA.epochOf(missing)).isEmpty();
  }

  @Test
  void alreadyMineRepopulatesTheOwnedSetAndAMutationThenSucceeds() {
    instanceA = boot();
    RoomOwnershipService ownershipA = instanceA.getBean(RoomOwnershipService.class);
    RoomEventExecutor executor = instanceA.getBean(RoomEventExecutor.class);
    UUID roomId = insertRoom(instanceA);
    ownershipA.claim(roomId);
    ownershipA.evictAfterFailedCas(roomId); // Redis still names this incarnation
    assertThat(ownershipA.epochOf(roomId)).isEmpty();

    RoomOwnershipService.ClaimOutcome outcome = ownershipA.claim(roomId);

    assertThat(outcome).isInstanceOf(RoomOwnershipService.ClaimOutcome.AlreadyMine.class);
    assertThat(ownershipA.epochOf(roomId)).contains(1L);
    String result =
        executor.execute(
            roomId,
            () ->
                new RoomEventExecutor.MutationOutcome<>(
                    "applied", new RoomEventExecutor.RoomEvent("TEST_EVENT", Map.of())));
    assertThat(result).isEqualTo("applied");
    assertThat(roomRow(instanceA, roomId).get("version")).isEqualTo(1L);
  }

  @Test
  void aPerRoomOwnershipMismatchEvictsOnlyThatRoom() {
    instanceA = boot();
    RoomOwnershipService ownershipA = instanceA.getBean(RoomOwnershipService.class);
    UUID keptRoom = insertRoom(instanceA);
    UUID stolenRoom = insertRoom(instanceA);
    ownershipA.claim(keptRoom);
    ownershipA.claim(stolenRoom);

    // A Redis flush/eviction lets someone else take the key without A's lease lapsing.
    StringRedisTemplate redis = instanceA.getBean(StringRedisTemplate.class);
    redis.opsForValue().set("room:" + stolenRoom + ":pod", "some-other-pod");

    ownershipA.renewAndRecheck();

    assertThat(ownershipA.epochOf(stolenRoom)).isEmpty();
    assertThat(ownershipA.epochOf(keptRoom)).isPresent();
  }

  @Test
  void adoptionAbandonsAndSelfEvictsWhenALaterTenureAlreadyRecordedItself() {
    instanceA = boot();
    RoomOwnershipService ownershipA = instanceA.getBean(RoomOwnershipService.class);
    UUID roomId = insertRoom(instanceA);
    // Forge a room row already owned at a far-future epoch, as if another instance's adoption
    // already committed — the claim script's own Redis-side epoch counter starts fresh at 0/1,
    // so the very first claim here is guaranteed to lose the Postgres-side guard race.
    instanceA
        .getBean(JdbcClient.class)
        .sql("UPDATE room SET owner_epoch = 100 WHERE room_id = :roomId")
        .param("roomId", roomId)
        .update();

    RoomOwnershipService.ClaimOutcome outcome = ownershipA.claim(roomId);

    assertThat(outcome).isInstanceOf(RoomOwnershipService.ClaimOutcome.Superseded.class);
    assertThat(ownershipA.epochOf(roomId)).isEmpty();
  }

  @Test
  void releaseIsGracefulAndNeverResetsTheEpoch() {
    instanceA = boot();
    RoomOwnershipService ownershipA = instanceA.getBean(RoomOwnershipService.class);
    UUID roomId = insertRoom(instanceA);
    ownershipA.claim(roomId);

    ownershipA.release(roomId);

    assertThat(ownershipA.epochOf(roomId)).isEmpty();
    Map<String, Object> row = roomRow(instanceA, roomId);
    assertThat(row.get("owner_instance_id")).isNull();
    assertThat(row.get("ownerless_since")).isNotNull();
    assertThat(((Number) Objects.requireNonNull(row.get("owner_epoch"))).longValue())
        .isEqualTo(1L); // never reset
    StringRedisTemplate redis = instanceA.getBean(StringRedisTemplate.class);
    assertThat(redis.opsForValue().get("room:" + roomId + ":pod")).isNull();
  }

  @Test
  void nextVersionAssignsFromTheAdoptedValueAndNeverGoesBackwards() {
    instanceA = boot();
    RoomOwnershipService ownershipA = instanceA.getBean(RoomOwnershipService.class);
    UUID roomId = insertRoom(instanceA);
    ownershipA.claim(roomId); // room starts at version 0

    assertThat(ownershipA.nextVersion(roomId)).isEqualTo(1L);
    assertThat(ownershipA.nextVersion(roomId)).isEqualTo(2L);
  }

  @Test
  void adoptionOpensAMassGraceReservationForEveryConnectedPlayer_IMP_GATE_48() {
    instanceA = boot();
    instanceB = boot();
    RoomOwnershipService ownershipA = instanceA.getBean(RoomOwnershipService.class);
    RoomOwnershipService ownershipB = instanceB.getBean(RoomOwnershipService.class);
    UUID roomId = insertRoom(instanceA);
    setRoomState(instanceA, roomId, "IN_PROGRESS");
    UUID connectedPlayer = insertParticipant(instanceA, roomId, "PLAYER", 1, 1);
    UUID alreadyInGrace = insertParticipant(instanceA, roomId, "PLAYER", 2, 1);
    UUID existingReservation = UUID.randomUUID();
    instanceA
        .getBean(JdbcClient.class)
        .sql(
            "update room_participant set connection_count = 0, grace_reservation_id = :res,"
                + " grace_expires_at = now() + interval '5 seconds'"
                + " where room_id = :roomId and user_id = :userId")
        .param("res", existingReservation)
        .param("roomId", roomId)
        .param("userId", alreadyInGrace)
        .update();
    UUID connectedSpectator = insertParticipant(instanceA, roomId, "SPECTATOR", 3, 1);
    ownershipA.claim(roomId);

    deleteLivenessKey(instanceA, instanceA.getBean(PodIdentity.class).id());
    ownershipB.claim(roomId); // adopts

    Map<String, Object> connectedRow = participantRow(instanceA, roomId, connectedPlayer);
    assertThat(((Number) Objects.requireNonNull(connectedRow.get("connection_count"))).intValue())
        .isZero();
    assertThat(connectedRow.get("grace_reservation_id")).isNotNull();
    assertThat(connectedRow.get("grace_expires_at")).isNotNull();

    Map<String, Object> alreadyInGraceRow = participantRow(instanceA, roomId, alreadyInGrace);
    assertThat(alreadyInGraceRow.get("grace_reservation_id")).isEqualTo(existingReservation);

    Map<String, Object> spectatorRow = participantRow(instanceA, roomId, connectedSpectator);
    assertThat(((Number) Objects.requireNonNull(spectatorRow.get("connection_count"))).intValue())
        .isZero();
    assertThat(spectatorRow.get("grace_reservation_id")).isNull(); // FR-DC-003: never reserved
  }

  private static void setRoomState(
      ConfigurableApplicationContext context, UUID roomId, String state) {
    context
        .getBean(JdbcClient.class)
        .sql("update room set state = :state where room_id = :roomId")
        .param("state", state)
        .param("roomId", roomId)
        .update();
  }

  private static UUID insertParticipant(
      ConfigurableApplicationContext context,
      UUID roomId,
      String role,
      long joinOrder,
      int connectionCount) {
    UUID userId = UUID.randomUUID();
    String username = "user" + joinOrder + "-" + userId.toString().substring(0, 8);
    JdbcClient jdbcClient = context.getBean(JdbcClient.class);
    jdbcClient
        .sql(
            "insert into app_user (user_id, kind, username, username_skeleton, guest_serial)"
                + " values (:userId, 'GUEST', :username, :username, :serial)")
        .param("userId", userId)
        .param("username", username)
        .param("serial", userId.getLeastSignificantBits() & Long.MAX_VALUE)
        .update();
    jdbcClient
        .sql(
            "insert into room_participant (room_id, user_id, role, join_order, connection_count)"
                + " values (:roomId, :userId, :role, :joinOrder, :connectionCount)")
        .param("roomId", roomId)
        .param("userId", userId)
        .param("role", role)
        .param("joinOrder", joinOrder)
        .param("connectionCount", connectionCount)
        .update();
    return userId;
  }

  private static Map<String, Object> participantRow(
      ConfigurableApplicationContext context, UUID roomId, UUID userId) {
    return context
        .getBean(JdbcClient.class)
        .sql("select * from room_participant where room_id = :roomId and user_id = :userId")
        .param("roomId", roomId)
        .param("userId", userId)
        .query()
        .singleRow();
  }

  private static ConfigurableApplicationContext boot() {
    return SpringApplication.run(
        TurnBaseApplication.class,
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
        // Keeps the background renewal tick from racing manual test manipulation of Redis state.
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

  private static Map<String, Object> roomRow(ConfigurableApplicationContext context, UUID roomId) {
    return context
        .getBean(JdbcClient.class)
        .sql("SELECT * FROM room WHERE room_id = :roomId")
        .param("roomId", roomId)
        .query()
        .singleRow();
  }

  private static void deleteLivenessKey(ConfigurableApplicationContext context, String podId) {
    context.getBean(StringRedisTemplate.class).delete("pod:" + podId + ":alive");
  }

  /** {@code calls=} of {@code INFO commandstats} for one command; 0 when it never ran. */
  static long redisCommandCalls(StringRedisTemplate redis, String command) {
    java.util.Properties stats =
        redis.execute(
            (org.springframework.data.redis.core.RedisCallback<java.util.Properties>)
                c -> c.serverCommands().info("commandstats"));
    String line = Objects.requireNonNull(stats).getProperty("cmdstat_" + command);
    if (line == null) {
      return 0;
    }
    return Long.parseLong(line.replaceFirst(".*calls=(\\d+).*", "$1"));
  }

  static void resetRedisStats(StringRedisTemplate redis) {
    redis.execute(
        (org.springframework.data.redis.core.RedisCallback<String>)
            c -> {
              c.serverCommands().resetConfigStats();
              return "OK";
            });
  }
}
