package io.turnbase.platform.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * {@code rate-limiting.md} § Client identity: deriving the IP — the two-sided trusted-proxy policy
 * and the IPv6 /64 keying rule.
 */
class ClientIpResolverTest {

  private final ClientIpResolver resolver =
      new ClientIpResolver(new SecurityProperties(List.of("10.0.0.0/8", "172.16.0.0/12")));

  @Test
  void untrustedPeerIgnoresXForwardedFor() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setRemoteAddr("203.0.113.7");
    request.addHeader("X-Forwarded-For", "198.51.100.9");

    assertThat(resolver.resolve(request)).isEqualTo("203.0.113.7");
  }

  @Test
  void trustedPeerHonoursXForwardedFor() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setRemoteAddr("10.1.2.3");
    request.addHeader("X-Forwarded-For", "198.51.100.9");

    assertThat(resolver.resolve(request)).isEqualTo("198.51.100.9");
  }

  @Test
  void trustedPeerWithNoForwardedHeaderUsesSocketAddress() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setRemoteAddr("172.16.5.6");

    assertThat(resolver.resolve(request)).isEqualTo("172.16.5.6");
  }

  @Test
  void aHostnameInTheForwardedHeaderIsRejectedRatherThanResolved() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setRemoteAddr("10.0.0.1");
    request.addHeader("X-Forwarded-For", "attacker.example.com");

    // Falls back to the (trusted) socket address rather than triggering a DNS lookup.
    assertThat(resolver.resolve(request)).isEqualTo("10.0.0.1");
  }

  @Test
  void aClientSuppliedPrefixInTheForwardedHeaderDoesNotChooseTheKey() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setRemoteAddr("10.0.0.1");
    request.addHeader("X-Forwarded-For", "192.0.2.99, 198.51.100.9");

    // The trusted hop's entry (rightmost) is the key; the forged leading entry is ignored.
    assertThat(resolver.resolve(request)).isEqualTo("198.51.100.9");
  }

  @Test
  void ipv4KeysOnTheFullAddress() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setRemoteAddr("198.51.100.42");

    assertThat(resolver.resolve(request)).isEqualTo("198.51.100.42");
  }

  @Test
  void ipv6KeysOnThe64BitPrefixNotTheFullAddress() {
    MockHttpServletRequest first = new MockHttpServletRequest();
    first.setRemoteAddr("2001:db8:1234:5678:aaaa:bbbb:cccc:dddd");
    MockHttpServletRequest second = new MockHttpServletRequest();
    second.setRemoteAddr("2001:db8:1234:5678:1111:2222:3333:4444");

    String firstKey = resolver.resolve(first);
    String secondKey = resolver.resolve(second);

    assertThat(firstKey).isEqualTo(secondKey).endsWith("/64");
  }

  @Test
  void ipv6AddressesInDifferentSlash64sKeyDifferently() {
    MockHttpServletRequest first = new MockHttpServletRequest();
    first.setRemoteAddr("2001:db8:1234:5678::1");
    MockHttpServletRequest second = new MockHttpServletRequest();
    second.setRemoteAddr("2001:db8:1234:9999::1");

    assertThat(resolver.resolve(first)).isNotEqualTo(resolver.resolve(second));
  }
}
