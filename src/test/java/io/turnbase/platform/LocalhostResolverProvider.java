package io.turnbase.platform;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.net.spi.InetAddressResolver;
import java.net.spi.InetAddressResolver.LookupPolicy;
import java.net.spi.InetAddressResolverProvider;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Test-JVM name resolution for {@code localhost} and {@code *.localhost}: always loopback, as RFC
 * 6761 §6.3 says, without asking the machine's resolver; every other name goes to the system
 * resolver unchanged.
 *
 * <p>The ITs reach the platform and game origins as {@code turnbase.localhost} and {@code
 * {gameId}.games.localhost} (local-environment.md §1). systemd-resolved and browsers answer those
 * names locally, but a host whose resolver is only {@code files dns} (a default WSL install) sends
 * them to its DNS server, which takes about 15 s to fail, so the tests failed on such a machine and
 * passed in CI. Registered in {@code META-INF/services}, so it applies to the whole test JVM.
 */
public final class LocalhostResolverProvider extends InetAddressResolverProvider {

  @Override
  public InetAddressResolver get(Configuration configuration) {
    InetAddressResolver system = configuration.builtinResolver();
    return new InetAddressResolver() {
      @Override
      public Stream<InetAddress> lookupByName(String host, LookupPolicy policy)
          throws UnknownHostException {
        if (isLocalhostName(host)) {
          return loopback(host, policy);
        }
        return system.lookupByName(host, policy);
      }

      @Override
      public String lookupByAddress(byte[] addr) throws UnknownHostException {
        return system.lookupByAddress(addr);
      }
    };
  }

  @Override
  public String name() {
    return "turnbase-test-localhost";
  }

  static boolean isLocalhostName(String host) {
    String name = host.toLowerCase(Locale.ROOT);
    if (name.endsWith(".")) {
      name = name.substring(0, name.length() - 1);
    }
    return name.equals("localhost") || name.endsWith(".localhost");
  }

  private static Stream<InetAddress> loopback(String host, LookupPolicy policy)
      throws UnknownHostException {
    int characteristics = policy.characteristics();
    boolean v4 = (characteristics & LookupPolicy.IPV4) != 0;
    boolean v6 = (characteristics & LookupPolicy.IPV6) != 0;
    InetAddress ipv4 = InetAddress.getByAddress(host, new byte[] {127, 0, 0, 1});
    InetAddress ipv6 = InetAddress.getByAddress(host, loopbackV6());
    if (v4 && v6) {
      return (characteristics & LookupPolicy.IPV6_FIRST) != 0
          ? Stream.of(ipv6, ipv4)
          : Stream.of(ipv4, ipv6);
    }
    return v6 ? Stream.of(ipv6) : Stream.of(ipv4);
  }

  private static byte[] loopbackV6() {
    byte[] address = new byte[16];
    address[15] = 1;
    return address;
  }
}
