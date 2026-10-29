package io.turnbase.platform.room;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * {@code room-lifecycle.md} §1.6, run on {@code SIGTERM} (the {@link ContextClosedEvent}, which
 * precedes the web server's own graceful stop and the closing of every bean). Readiness has already
 * failed by then (Spring Boot's shutdown hook, plus the {@code preStop} pause that lets the
 * endpoint removal propagate). Order: refuse new claims, then per owned room take the room lock (so
 * an in-flight mutation finishes), tell its clients to reconnect, and release it; last, delete the
 * liveness key. The next claimant takes the free branch with no lease wait.
 */
@Component
class RoomDrain {

  private static final Logger log = LoggerFactory.getLogger(RoomDrain.class);

  private final RoomOwnershipService ownership;
  private final RoomEventExecutor events;
  private final ApplicationEventPublisher publisher;
  private final ApplicationContext context;

  RoomDrain(
      RoomOwnershipService ownership,
      RoomEventExecutor events,
      ApplicationEventPublisher publisher,
      ApplicationContext context) {
    this.context = context;
    this.ownership = ownership;
    this.events = events;
    this.publisher = publisher;
  }

  /**
   * The management port's child context publishes its own {@link ContextClosedEvent}, which reaches
   * this parent listener too: only the event of this component's own context drains.
   */
  @EventListener
  void onShutdown(ContextClosedEvent event) {
    if (event.getApplicationContext() == context) {
      drain();
    }
  }

  void drain() {
    ownership.beginDrain();
    int released = 0;
    for (var roomId : ownership.ownedRoomIds()) {
      try {
        events.withLock(
            roomId,
            () -> {
              publisher.publishEvent(new RoomDrainingEvent(roomId));
              ownership.release(roomId);
              return null;
            });
        released++;
        log.info("drain released room {}", roomId);
      } catch (RuntimeException e) {
        // Not owned any more, or the lock timed out: the lease expiry covers a room left behind.
        log.warn("drain could not release room {}: {}", roomId, e.toString());
      }
    }
    try {
      ownership.retireLiveness();
    } catch (RuntimeException e) {
      log.warn("drain could not delete the liveness key: {}", e.toString());
    }
    log.info("drain finished, {} rooms released", released);
  }
}
