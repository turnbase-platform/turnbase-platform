package io.turnbase.platform.common;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Per-key {@link ReentrantLock}s that exist only while somebody holds or awaits them (IMP-M10-14).
 * A plain {@code computeIfAbsent} map keeps one lock per key forever; evicting by idle time instead
 * would be unsafe, because a lock evicted while held lets a second thread lock a fresh object for
 * the same key. Reference counting makes eviction exact: the entry goes when the last user leaves.
 *
 * <p>Usage: {@code lock = locks.retain(key); try { lock.lock(); ... } finally { unlock();
 * locks.release(key); }} — every {@code retain} needs exactly one {@code release}.
 */
public final class KeyedLocks<K> {

  private static final class Entry {
    final ReentrantLock lock = new ReentrantLock();
    int users;
  }

  private final Map<K, Entry> entries = new ConcurrentHashMap<>();

  /** Registers interest in {@code key} and returns its lock (not yet locked). */
  public ReentrantLock retain(K key) {
    return entries.compute(
            key,
            (k, existing) -> {
              Entry entry = existing == null ? new Entry() : existing;
              entry.users++;
              return entry;
            })
        .lock;
  }

  /** Ends one {@link #retain}; the last release drops the entry. */
  public void release(K key) {
    entries.computeIfPresent(key, (k, entry) -> --entry.users == 0 ? null : entry);
  }

  /** Number of keys currently retained. */
  public int size() {
    return entries.size();
  }
}
