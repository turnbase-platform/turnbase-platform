package io.turnbase.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * The ITs reach the two origins as {@code turnbase.localhost} and {@code {gameId}.games.localhost};
 * {@link LocalhostResolverProvider} makes those resolve to loopback in the test JVM whatever the
 * machine's resolver does (RFC 6761 §6.3).
 */
class LocalhostResolverTest {

  @Test
  void aGameOriginNameResolvesToLoopbackWithoutTheSystemResolver() throws Exception {
    long start = System.nanoTime();
    InetAddress[] addresses = InetAddress.getAllByName("some-game.games.localhost");
    Duration took = Duration.ofNanos(System.nanoTime() - start);

    assertThat(addresses).isNotEmpty().allMatch(InetAddress::isLoopbackAddress);
    assertThat(took).isLessThan(Duration.ofSeconds(1));
  }

  @Test
  void thePlatformOriginNameResolvesToLoopback() throws Exception {
    assertThat(InetAddress.getAllByName("turnbase.localhost"))
        .isNotEmpty()
        .allMatch(InetAddress::isLoopbackAddress);
  }

  @Test
  void otherNamesAndAddressesAreLeftToTheSystemResolver() throws Exception {
    assertThat(InetAddress.getByName("127.0.0.1").isLoopbackAddress()).isTrue();
    assertThat(InetAddress.getByAddress(new byte[] {127, 0, 0, 1}).getHostAddress())
        .isEqualTo("127.0.0.1");
  }
}
