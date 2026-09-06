package io.turnbase.platform.session;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Service;

/**
 * Issues and verifies the session token of secrets-and-configuration.md §3 — HS256 over the current
 * key set, {@code kid} in the header naming which key signed it. New tokens are always signed with
 * {@link JwtKeySet#active()}; verification accepts {@code active} or any key in {@code retired}
 * (§3.2), which is what makes graceful rotation invisible to a live session instead of a mass
 * logout.
 */
@Service
public class JwtService {

  private final JwtKeySetManager keySet;

  JwtService(JwtKeySetManager keySet) {
    this.keySet = keySet;
  }

  /** §3.5's claim shape: {@code sub}, {@code jti}, {@code kid}, {@code iat}, {@code exp}. */
  public String issue(UUID userId, UUID sessionId, Instant issuedAt, Instant expiresAt) {
    JwtKeySet.ActiveKey active = keySet.current().active();
    return Jwts.builder()
        .header()
        .keyId(active.kid())
        .and()
        .subject(userId.toString())
        .id(sessionId.toString())
        .issuedAt(Date.from(issuedAt))
        .expiration(Date.from(expiresAt))
        .signWith(hmacKey(active.secret()), Jwts.SIG.HS256)
        .compact();
  }

  /**
   * Accepts a token signed by {@link JwtKeySet#active()} or any key in {@link JwtKeySet#retired()}.
   * The {@code kid} header selects the key; a {@code kid} naming neither is the same failure as a
   * bad signature — both mean "not verifiable against the set we hold now".
   *
   * @throws JwtVerificationException the token is expired, malformed, has a bad signature, or names
   *     a {@code kid} not in the current key set
   */
  public JwtClaims verify(String token) {
    Jws<Claims> jws;
    try {
      jws =
          Jwts.parser()
              .keyLocator(
                  header -> {
                    Object kid = header.get("kid");
                    if (!(kid instanceof String kidString)) {
                      throw new JwtVerificationException("token header carries no kid");
                    }
                    return keySet
                        .current()
                        .secretFor(kidString)
                        .map(JwtService::hmacKey)
                        .orElseThrow(
                            () ->
                                new JwtVerificationException(
                                    "no key in the current set matches kid " + kidString));
                  })
              .build()
              .parseSignedClaims(token);
    } catch (JwtException e) {
      throw new JwtVerificationException("session token failed verification", e);
    }

    Claims claims = jws.getPayload();
    Object kid = jws.getHeader().get("kid");
    if (!(kid instanceof String kidString)) {
      throw new JwtVerificationException("verified token header carries no kid");
    }
    return new JwtClaims(
        UUID.fromString(claims.getSubject()),
        UUID.fromString(claims.getId()),
        kidString,
        claims.getIssuedAt().toInstant(),
        claims.getExpiration().toInstant());
  }

  private static SecretKey hmacKey(String base64Secret) {
    return Keys.hmacShaKeyFor(Base64.getDecoder().decode(base64Secret));
  }
}
