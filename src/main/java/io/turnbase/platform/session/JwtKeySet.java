package io.turnbase.platform.session;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * secrets-and-configuration.md §3.2 — a key set, not a key. New tokens are signed with {@code
 * active}; verification accepts {@code active} or any key in {@code retired}, which is what makes
 * rotation (§3.3) invisible to a live session instead of a mass logout.
 */
public record JwtKeySet(
    @JsonProperty("active") ActiveKey active, @JsonProperty("retired") List<RetiredKey> retired) {

  public record ActiveKey(
      @JsonProperty("kid") String kid,
      @JsonProperty("secret") String secret,
      @JsonProperty("notBefore") Instant notBefore) {}

  public record RetiredKey(
      @JsonProperty("kid") String kid,
      @JsonProperty("secret") String secret,
      @JsonProperty("retiredAt") Instant retiredAt) {}

  /**
   * The secret for {@code kid}, from {@code active} or {@code retired} — empty if neither holds it.
   */
  public Optional<String> secretFor(String kid) {
    if (active.kid().equals(kid)) {
      return Optional.of(active.secret());
    }
    return retired.stream().filter(k -> k.kid().equals(kid)).map(RetiredKey::secret).findFirst();
  }
}
