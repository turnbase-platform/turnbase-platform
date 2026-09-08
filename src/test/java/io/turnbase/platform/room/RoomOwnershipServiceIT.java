package io.turnbase.platform.room;

import static org.assertj.core.api.Assertions.assertThat;

import io.turnbase.platform.PostgresIntegrationTest;
import io.turnbase.platform.TurnBaseApplication;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

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
}
