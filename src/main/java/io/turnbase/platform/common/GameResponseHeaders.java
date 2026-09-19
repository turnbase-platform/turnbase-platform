package io.turnbase.platform.common;

import java.security.SecureRandom;
import java.util.Base64;
import org.springframework.stereotype.Component;

/**
 * {@code game-asset-serving.md} §6.4 — one CSP, sent identically on both game routes of §4.1 (the
 * bootstrap document and every static asset). Public so both this item's asset route and the
 * bootstrap-document route ({@code IMP-M3-05}) build the exact same header from one place, per
 * §6.1's "the two must not be changed independently" rule for the sandbox this CSP pairs with.
 */
@Component
public class GameResponseHeaders {

  private static final SecureRandom RANDOM = new SecureRandom();

  private final PlatformOriginProperties platformOrigin;

  public GameResponseHeaders(PlatformOriginProperties platformOrigin) {
    this.platformOrigin = platformOrigin;
  }

  /** A fresh nonce per response — {@code script-src}'s {@code 'nonce-{nonce}'} never repeats. */
  public String newNonce() {
    byte[] bytes = new byte[16];
    RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  public String csp(String nonce) {
    return "default-src 'none'; "
        + "script-src 'self' 'wasm-unsafe-eval' 'nonce-"
        + nonce
        + "'; "
        + "style-src 'self' 'unsafe-inline'; "
        + "img-src 'self' data: blob:; "
        + "media-src 'self' data: blob:; "
        + "font-src 'self' data:; "
        + "connect-src 'self'; "
        + "worker-src 'self' blob:; "
        + "frame-src 'none'; "
        + "form-action 'none'; "
        + "base-uri 'self'; "
        + "frame-ancestors "
        + platformOrigin.origin()
        + ";";
  }
}
