package io.turnbase.platform.common;

import jakarta.servlet.http.HttpServletRequest;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * {@code rate-limiting.md} § Client identity: deriving the IP. Two-sided by design — {@code
 * X-Forwarded-For} is honoured only when the socket peer is a configured trusted proxy, otherwise a
 * spoofed header would bypass Tier 1 or frame an innocent IP into lockout. IPv6 keys on the /64
 * prefix, never the full address, or a single residential allocation (2^64 addresses) would make
 * every IP-based tier trivially bypassable.
 */
class ClientIpResolver {

  private static final String FORWARDED_FOR_HEADER = "X-Forwarded-For";

  private final List<CidrRange> trustedProxies;

  ClientIpResolver(SecurityProperties securityProperties) {
    this.trustedProxies =
        securityProperties.trustedProxies().stream().map(CidrRange::parse).toList();
  }

  /** The rate-limit key for this request's client — a full IPv4 address or a masked IPv6 /64. */
  String resolve(HttpServletRequest request) {
    InetAddress socketAddress = parseLiteral(request.getRemoteAddr());
    InetAddress clientAddress = socketAddress;
    if (socketAddress != null && isTrusted(socketAddress)) {
      String forwardedFor = request.getHeader(FORWARDED_FOR_HEADER);
      InetAddress forwarded = forwardedFor == null ? null : parseLiteral(forwardedFor.strip());
      if (forwarded != null) {
        clientAddress = forwarded;
      }
    }
    if (clientAddress == null) {
      // Not a parseable IP literal at all (should not happen for a real socket peer) — fall back
      // to the raw string so a request is still keyed on *something* rather than crashing.
      return request.getRemoteAddr();
    }
    return keyFor(clientAddress);
  }

  private boolean isTrusted(InetAddress address) {
    return trustedProxies.stream().anyMatch(range -> range.contains(address));
  }

  private static String keyFor(InetAddress address) {
    if (address instanceof Inet6Address) {
      byte[] masked = Arrays.copyOf(address.getAddress(), 16);
      Arrays.fill(masked, 8, 16, (byte) 0);
      try {
        return InetAddress.getByAddress(masked).getHostAddress() + "/64";
      } catch (UnknownHostException e) {
        // Unreachable: a 16-byte array is always a valid IPv6 literal to InetAddress.getByAddress.
        throw new IllegalStateException(e);
      }
    }
    return address.getHostAddress();
  }

  /**
   * Parses a literal IPv4/IPv6 address without ever performing a DNS lookup. {@link
   * InetAddress#getByName} only skips resolution for a literal, so a non-literal string (a spoofed
   * hostname in {@code X-Forwarded-For}, say) must be rejected before it ever reaches that call.
   */
  private static @Nullable InetAddress parseLiteral(String value) {
    if (value.isEmpty() || !looksLikeIpLiteral(value)) {
      return null;
    }
    try {
      return InetAddress.getByName(value);
    } catch (UnknownHostException e) {
      return null;
    }
  }

  private static boolean looksLikeIpLiteral(String value) {
    return value
        .chars()
        .allMatch(c -> c == '.' || c == ':' || c == '%' || Character.digit(c, 16) != -1);
  }

  /** Dependency-free CIDR containment, generic over IPv4 and IPv6. */
  private static final class CidrRange {
    private final byte[] networkBytes;
    private final int prefixLength;

    private CidrRange(byte[] networkBytes, int prefixLength) {
      this.networkBytes = networkBytes;
      this.prefixLength = prefixLength;
    }

    static CidrRange parse(String cidr) {
      int slash = cidr.indexOf('/');
      String addressPart = slash < 0 ? cidr : cidr.substring(0, slash);
      try {
        InetAddress address = InetAddress.getByName(addressPart);
        int maxPrefix = address.getAddress().length * 8;
        int prefix = slash < 0 ? maxPrefix : Integer.parseInt(cidr.substring(slash + 1));
        return new CidrRange(address.getAddress(), prefix);
      } catch (UnknownHostException e) {
        throw new IllegalArgumentException(
            "turnbase.security.trusted-proxies entry is not a valid CIDR range: " + cidr, e);
      }
    }

    boolean contains(InetAddress candidate) {
      byte[] candidateBytes = candidate.getAddress();
      if (candidateBytes.length != networkBytes.length) {
        return false;
      }
      int fullBytes = prefixLength / 8;
      for (int i = 0; i < fullBytes; i++) {
        if (candidateBytes[i] != networkBytes[i]) {
          return false;
        }
      }
      int remainingBits = prefixLength % 8;
      if (remainingBits == 0) {
        return true;
      }
      int mask = 0xFF << (8 - remainingBits) & 0xFF;
      return (candidateBytes[fullBytes] & mask) == (networkBytes[fullBytes] & mask);
    }
  }
}
