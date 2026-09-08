package io.turnbase.platform.session;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Service;

/**
 * Signs and verifies the OAuth {@code state} parameter ({@code secrets-and-configuration.md} §5,
 * {@code oauth-rest-api.md} §3) — a short-lived, signed carrier for the post-login redirect
 * destination, over the same key set {@link JwtService} uses for session tokens. A separate claim
 * shape from the session token's on purpose: there is no principal yet at this point in the flow,
 * so there is no {@code sub}/{@code jti} to carry.
 */
@Service
public class OAuthStateService {

  private static final String DESTINATION_CLAIM = "dst";

  private final JwtKeySetManager keySet;

  OAuthStateService(JwtKeySetManager keySet) {
    this.keySet = keySet;
  }

  public String issue(String destination, Instant issuedAt, Instant expiresAt) {
    JwtKeySet.ActiveKey active = keySet.current().active();
    return Jwts.builder()
        .header()
        .keyId(active.kid())
        .and()
        .claim(DESTINATION_CLAIM, destination)
        .issuedAt(Date.from(issuedAt))
        .expiration(Date.from(expiresAt))
        .signWith(hmacKey(active.secret()), Jwts.SIG.HS256)
        .compact();
  }

  /**
   * @throws JwtVerificationException {@code state} is expired, malformed, has a bad signature, or
   *     names a {@code kid} not in the current key set — the same failure shape for all of them,
   *     since {@code state} is attacker-influenceable input and none of these should be
   *     distinguished in the response it produces (a redirect to {@code /login}).
   */
  public String verify(String state) {
    Jws<Claims> jws;
    try {
      jws =
          Jwts.parser()
              .keyLocator(
                  header -> {
                    Object kid = header.get("kid");
                    if (!(kid instanceof String kidString)) {
                      throw new JwtVerificationException("state header carries no kid");
                    }
                    return keySet
                        .current()
                        .secretFor(kidString)
                        .map(OAuthStateService::hmacKey)
                        .orElseThrow(
                            () ->
                                new JwtVerificationException(
                                    "no key in the current set matches kid " + kidString));
                  })
              .build()
              .parseSignedClaims(state);
    } catch (JwtException e) {
      throw new JwtVerificationException("state failed verification", e);
    }

    Object destination = jws.getPayload().get(DESTINATION_CLAIM);
    if (!(destination instanceof String destinationString)) {
      throw new JwtVerificationException("verified state carries no destination claim");
    }
    return destinationString;
  }

  private static SecretKey hmacKey(String base64Secret) {
    return Keys.hmacShaKeyFor(Base64.getDecoder().decode(base64Secret));
  }
}
