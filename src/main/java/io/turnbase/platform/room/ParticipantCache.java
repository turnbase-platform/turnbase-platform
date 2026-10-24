package io.turnbase.platform.room;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.LongSupplier;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * {@code IMP-M18-04} / {@code load-testing-phase-b.md} §3.3: the owning instance's memory of "who
 * is in this room's session", so a move by a participant it has already seen issues no Postgres
 * statement. Only the owner mutates a room (ADR-0008) and every mutation runs inside {@link
 * RoomEventExecutor#execute}, which brackets it with {@link #mutationStarted} and {@link
 * #mutationFinished}; that is what keeps the cache equal to what a Postgres read would answer.
 *
 * <p>A cache that cannot prove itself current is a miss. An entry is served only while
 *
 * <ul>
 *   <li>no mutation of the room is running (between the two brackets the database is changing under
 *       the entry, so a read goes to Postgres exactly as it did before the cache);
 *   <li>this instance still owns the room at the epoch the entry was read under (ownership lost and
 *       re-claimed is a different epoch; {@link #onRoomOwnershipLost} and {@link #onRoomClosed}
 *       only free the memory);
 *   <li>it is younger than {@link #MAX_AGE}, a bound on the one writer outside the executor:
 *       account erasure marks the user's rows left in its own transaction, but it revokes every
 *       session of that user in the same step, so no move of theirs can arrive.
 * </ul>
 *
 * <p>A fill races with a mutation: the reader takes a {@link #token()} before it reads Postgres,
 * and {@link #store} refuses the view if the room was mutated (or is being mutated) since.
 */
@Component
class ParticipantCache {

  /** Upper bound on how long an entry outlives a change made outside the executor. */
  static final long MAX_AGE = TimeUnit.SECONDS.toNanos(30);

  private final RoomOwnershipService ownership;
  private final LongSupplier nanoClock;
  private final AtomicLong tokens = new AtomicLong();
  private final Map<UUID, Slot> slots = new ConcurrentHashMap<>();
  private final Map<UUID, UUID> roomBySession = new ConcurrentHashMap<>();

  @Autowired
  ParticipantCache(RoomOwnershipService ownership) {
    this(ownership, System::nanoTime);
  }

  ParticipantCache(RoomOwnershipService ownership, LongSupplier nanoClock) {
    this.ownership = ownership;
    this.nanoClock = nanoClock;
  }

  /** What a participant lookup is answered from: the cache, or {@code load} on a miss. */
  Optional<SessionRoomView> viewOf(UUID sessionId, Function<UUID, Optional<SessionRoomView>> load) {
    Optional<SessionRoomView> cached = find(sessionId);
    if (cached.isPresent()) {
      return cached;
    }
    long token = token();
    Optional<SessionRoomView> loaded = load.apply(sessionId);
    loaded.ifPresent(view -> store(token, view));
    return loaded;
  }

  Optional<SessionRoomView> find(UUID sessionId) {
    UUID roomId = roomBySession.get(sessionId);
    if (roomId == null) {
      return Optional.empty();
    }
    Slot slot = slots.get(roomId);
    if (slot == null) {
      return Optional.empty();
    }
    synchronized (slot) {
      Entry entry = slot.entry;
      if (entry == null || slot.mutations > 0 || !entry.view().sessionId().equals(sessionId)) {
        return Optional.empty();
      }
      boolean owned = ownership.epochOf(roomId).filter(e -> e == entry.epoch()).isPresent();
      boolean fresh = nanoClock.getAsLong() - entry.readAt() < MAX_AGE;
      return owned && fresh ? Optional.of(entry.view()) : Optional.empty();
    }
  }

  /** Taken before the Postgres read that {@link #store} will be handed. */
  long token() {
    return tokens.get();
  }

  void store(long token, SessionRoomView view) {
    UUID roomId = view.roomId();
    Optional<Long> epoch = ownership.epochOf(roomId);
    if (epoch.isEmpty()) {
      return;
    }
    Slot slot = slots.computeIfAbsent(roomId, id -> new Slot(tokens.get()));
    synchronized (slot) {
      if (slot.mutations > 0 || slot.changedAt > token) {
        return;
      }
      forget(slot);
      slot.entry = new Entry(view, epoch.get(), nanoClock.getAsLong());
      roomBySession.put(view.sessionId(), roomId);
    }
  }

  /**
   * The room is about to change; every read of it goes to Postgres until {@link #mutationFinished}.
   */
  void mutationStarted(UUID roomId) {
    Slot slot = slots.computeIfAbsent(roomId, id -> new Slot(tokens.get()));
    synchronized (slot) {
      slot.mutations++;
      slot.changedAt = tokens.incrementAndGet();
      forget(slot);
    }
  }

  /** The mutation committed or failed; either way what was read before it is not to be trusted. */
  void mutationFinished(UUID roomId) {
    Slot slot = slots.get(roomId);
    if (slot == null) {
      return;
    }
    synchronized (slot) {
      slot.mutations--;
      slot.changedAt = tokens.incrementAndGet();
      forget(slot);
    }
  }

  @EventListener
  void onRoomOwnershipLost(RoomOwnershipLostEvent event) {
    drop(event.roomId());
  }

  @EventListener
  void onRoomClosed(RoomClosedEvent event) {
    drop(event.roomId());
  }

  private void drop(UUID roomId) {
    long changedAt = tokens.incrementAndGet();
    Slot slot = slots.get(roomId);
    if (slot != null) {
      synchronized (slot) {
        slot.changedAt = changedAt;
        forget(slot);
        if (slot.mutations == 0) {
          slots.remove(roomId, slot);
        }
      }
    }
  }

  int cachedRooms() {
    return roomBySession.size();
  }

  private void forget(Slot slot) {
    Entry entry = slot.entry;
    if (entry != null) {
      roomBySession.remove(entry.view().sessionId(), entry.view().roomId());
      slot.entry = null;
    }
  }

  private record Entry(SessionRoomView view, long epoch, long readAt) {}

  private static final class Slot {
    @Nullable Entry entry;
    int mutations;

    /** The token of the last change; a slot created later starts at the then-current token. */
    long changedAt;

    Slot(long changedAt) {
      this.changedAt = changedAt;
    }
  }
}
