package io.turnbase.platform.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * {@code IMP-M16-07}: the lease renewal and the ownership re-check run on different threads, so a
 * re-check that is blocked (a slow Redis, a huge owned set) cannot delay the renewal past the lease
 * TTL — the lapse that load-testing finding 14 saw.
 */
class RoomOwnershipSchedulingConfigTest {

  @Test
  void leaseRenewalKeepsRunningOnTimeWhileTheRecheckIsBlocked() throws Exception {
    CountDownLatch recheckEntered = new CountDownLatch(1);
    CountDownLatch releaseRecheck = new CountDownLatch(1);
    AtomicInteger renewals = new AtomicInteger();
    AtomicReference<String> renewalThread = new AtomicReference<>();
    AtomicReference<String> recheckThread = new AtomicReference<>();
    RoomOwnershipService ownership = mock(RoomOwnershipService.class);
    doAnswer(
            invocation -> {
              renewalThread.set(Thread.currentThread().getName());
              renewals.incrementAndGet();
              return null;
            })
        .when(ownership)
        .renewLease();
    doAnswer(
            invocation -> {
              recheckThread.set(Thread.currentThread().getName());
              recheckEntered.countDown();
              releaseRecheck.await();
              return null;
            })
        .when(ownership)
        .recheckOwnership();
    RoomOwnershipSchedulingConfig config =
        new RoomOwnershipSchedulingConfig(
            ownership,
            new RoomProperties(
                Duration.ofSeconds(60),
                Duration.ofSeconds(20),
                Duration.ofMinutes(30),
                8,
                new RoomProperties.Lease(
                    Duration.ofSeconds(10), Duration.ofMillis(50), Duration.ofSeconds(6))));
    config.start();
    try {
      assertThat(recheckEntered.await(5, TimeUnit.SECONDS)).isTrue();
      int atBlock = renewals.get();

      Thread.sleep(500);

      // ~10 intervals elapsed with the re-check stuck: the renewal must have kept ticking.
      assertThat(renewals.get() - atBlock).isGreaterThanOrEqualTo(5);
      assertThat(renewalThread.get()).startsWith("lease-renewer");
      assertThat(recheckThread.get())
          .startsWith("ownership-recheck")
          .isNotEqualTo(renewalThread.get());
    } finally {
      releaseRecheck.countDown();
      config.stop();
    }
  }
}
