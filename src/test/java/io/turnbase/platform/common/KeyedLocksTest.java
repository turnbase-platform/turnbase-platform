package io.turnbase.platform.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import org.junit.jupiter.api.Test;

/** IMP-M10-14: per-key locks are reclaimed once nobody holds or awaits them. */
class KeyedLocksTest {

  private final KeyedLocks<UUID> locks = new KeyedLocks<>();

  @Test
  void distinctKeyChurnLeavesNoEntries() {
    for (int i = 0; i < 10_000; i++) {
      UUID key = UUID.randomUUID();
      ReentrantLock lock = locks.retain(key);
      lock.lock();
      lock.unlock();
      locks.release(key);
    }
    assertThat(locks.size()).isZero();
  }

  @Test
  void aHeldLockIsNotEvictedAndStaysTheSameObject() {
    UUID key = UUID.randomUUID();
    ReentrantLock holder = locks.retain(key);
    holder.lock();
    ReentrantLock waiter = locks.retain(key);
    assertThat(waiter).isSameAs(holder);
    holder.unlock();
    locks.release(key);
    assertThat(locks.size()).isEqualTo(1);
    assertThat(locks.retain(key)).isSameAs(holder);
    locks.release(key);
    locks.release(key);
    assertThat(locks.size()).isZero();
  }
}
