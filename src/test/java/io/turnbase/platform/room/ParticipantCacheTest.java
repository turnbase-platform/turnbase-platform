package io.turnbase.platform.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * {@code IMP-M18-04}: whatever order joins, leaves, kicks, disconnects, reconnects, host transfers,
 * ownership loss and moves arrive in, the answer the cache gives equals the answer Postgres would.
 * The "database" is a list the test mutates only between {@code mutationStarted} and {@code
 * mutationFinished}, exactly as {@link RoomEventExecutor#execute} brackets a mutation.
 */
class ParticipantCacheTest {

  private final UUID roomId = UUID.randomUUID();
  private final UUID sessionId = UUID.randomUUID();
  private final RoomOwnershipService ownership = mock(RoomOwnershipService.class);
  private final AtomicLong clock = new AtomicLong();
  private final ParticipantCache cache = new ParticipantCache(ownership, clock::get);
  private final AtomicInteger reads = new AtomicInteger();

  private final List<SessionRoomView.Member> members = new ArrayList<>();
  private String state = "IN_PROGRESS";
  private long epoch = 1;
  private boolean owned = true;

  private Optional<SessionRoomView> database(UUID session) {
    reads.incrementAndGet();
    if (!session.equals(sessionId) || members.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(new SessionRoomView(roomId, sessionId, state, List.copyOf(members)));
  }

  private void owning() {
    when(ownership.epochOf(roomId)).thenAnswer(i -> owned ? Optional.of(epoch) : Optional.empty());
  }

  private Optional<RoomRepository.ActingParticipant> uncached(UUID user) {
    return database(sessionId).flatMap(v -> v.actingParticipant(user));
  }

  private Optional<RoomRepository.ActingParticipant> cached(UUID user) {
    return cache.viewOf(sessionId, this::database).flatMap(v -> v.actingParticipant(user));
  }

  @Test
  void aSecondLookupIsServedWithoutReadingTheDatabase() {
    owning();
    UUID user = UUID.randomUUID();
    members.add(new SessionRoomView.Member(user, "PLAYER", UUID.randomUUID(), "ann"));

    cached(user);
    int before = reads.get();

    assertThat(cached(user)).isPresent();
    assertThat(reads.get()).isEqualTo(before);
  }

  @Test
  void aViewReadBeforeAMutationIsNotStoredAfterIt() {
    owning();
    UUID user = UUID.randomUUID();
    members.add(new SessionRoomView.Member(user, "PLAYER", UUID.randomUUID(), "ann"));
    long token = cache.token();
    SessionRoomView stale = database(sessionId).orElseThrow();

    cache.mutationStarted(roomId);
    members.clear();
    cache.mutationFinished(roomId);
    cache.store(token, stale);

    assertThat(cache.find(sessionId)).isEmpty();
  }

  @Test
  void nothingIsServedOrStoredWhileAMutationRuns() {
    owning();
    UUID user = UUID.randomUUID();
    members.add(new SessionRoomView.Member(user, "PLAYER", UUID.randomUUID(), "ann"));
    cached(user);

    cache.mutationStarted(roomId);
    assertThat(cache.find(sessionId)).isEmpty();
    cache.store(cache.token(), database(sessionId).orElseThrow());
    cache.mutationFinished(roomId);

    assertThat(cache.find(sessionId)).isEmpty();
  }

  @Test
  void anEntryDoesNotSurviveALostAndReclaimedRoomOrItsAge() {
    owning();
    UUID user = UUID.randomUUID();
    members.add(new SessionRoomView.Member(user, "PLAYER", UUID.randomUUID(), "ann"));
    cached(user);

    epoch = 2;
    assertThat(cache.find(sessionId)).as("a new epoch").isEmpty();

    cached(user);
    assertThat(cache.find(sessionId)).isPresent();
    clock.addAndGet(ParticipantCache.MAX_AGE);
    assertThat(cache.find(sessionId)).as("too old").isEmpty();

    cached(user);
    owned = false;
    assertThat(cache.find(sessionId)).as("not owned").isEmpty();
  }

  @Test
  void aClosureOrAnOwnershipLossFreesTheMemory() {
    owning();
    members.add(new SessionRoomView.Member(UUID.randomUUID(), "PLAYER", null, "ann"));
    cached(members.get(0).userId());
    assertThat(cache.cachedRooms()).isEqualTo(1);

    cache.onRoomOwnershipLost(new RoomOwnershipLostEvent(roomId, "test"));

    assertThat(cache.cachedRooms()).isZero();
  }

  @Test
  void theCachedDecisionEqualsTheUncachedOneAcrossRandomInterleavings() {
    owning();
    for (long seed = 0; seed < 300; seed++) {
      Random random = new Random(seed);
      members.clear();
      state = "IN_PROGRESS";
      owned = true;
      epoch = 1;
      List<UUID> users = new ArrayList<>();
      for (int i = 0; i < 6; i++) {
        users.add(UUID.randomUUID());
      }
      members.add(new SessionRoomView.Member(users.get(0), "PLAYER", UUID.randomUUID(), "u0"));

      for (int step = 0; step < 60; step++) {
        UUID user = users.get(random.nextInt(users.size()));
        switch (random.nextInt(9)) {
          case 0 -> mutate(() -> join(user, "PLAYER")); // join
          case 1 -> mutate(() -> join(user, "SPECTATOR"));
          case 2 -> mutate(() -> members.removeIf(m -> m.userId().equals(user))); // leave, kick
          case 3 -> mutate(() -> {}); // disconnect / reconnect / host transfer: no decision change
          case 4 -> mutate(() -> state = random.nextBoolean() ? "IN_PROGRESS" : "LOBBY");
          case 5 -> {
            owned = false; // ownership loss, then a re-claim at a new epoch
            cache.onRoomOwnershipLost(new RoomOwnershipLostEvent(roomId, "test"));
            if (random.nextBoolean()) {
              owned = true;
              epoch++;
            }
          }
          case 6 -> {
            // the owner's database changes under a fill that started earlier
            long token = cache.token();
            Optional<SessionRoomView> read = database(sessionId);
            mutate(() -> members.removeIf(m -> m.userId().equals(user)));
            read.ifPresent(view -> cache.store(token, view));
          }
          case 7 -> clock.addAndGet(random.nextInt(2) == 0 ? 1 : ParticipantCache.MAX_AGE);
          default -> {}
        }
        if (!owned) {
          owned = random.nextBoolean();
          epoch++;
        }
        for (UUID u : users) {
          assertThat(cached(u)).as("seed %d step %d", seed, step).isEqualTo(uncached(u));
        }
      }
    }
  }

  private void join(UUID user, String role) {
    members.removeIf(m -> m.userId().equals(user));
    members.add(
        new SessionRoomView.Member(
            user, role, "PLAYER".equals(role) ? UUID.randomUUID() : null, "n" + members.size()));
  }

  private void mutate(Runnable change) {
    cache.mutationStarted(roomId);
    try {
      change.run();
    } finally {
      cache.mutationFinished(roomId);
    }
  }
}
