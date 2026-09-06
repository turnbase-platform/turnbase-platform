package io.turnbase.platform.session;

import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * secrets-and-configuration.md §3.3's three-step rotation. Step 2 ("wait out the session lifetime")
 * is a passage of time between {@link #promote()} and {@link #retire(String)}, not something this
 * class enforces — an emergency rotation (§3.3, "skips step 2") retires immediately, which is
 * exactly what calling {@link #retire(String)} right after {@link #promote()} does.
 */
@Service
public class JwtKeyRotationService {

  static final String KEY_ROTATED_REASON = "KEY_ROTATED";

  private final JwtKeySetManager keySetManager;
  private final UserSessionRepository sessions;

  JwtKeyRotationService(JwtKeySetManager keySetManager, UserSessionRepository sessions) {
    this.keySetManager = keySetManager;
    this.sessions = sessions;
  }

  /** Promotes a freshly generated key to {@code active}; the previous active key is retired. */
  public JwtKeySet.ActiveKey promote() {
    return keySetManager.promote();
  }

  /**
   * Removes {@code kid} from the key set and revokes every live session still bound to it ({@code
   * revoked_reason = 'KEY_ROTATED'}, §3.3 step 3) in the same transaction — a session revoked
   * without the key disappearing (or the reverse) would let the two disagree about whether the
   * token that named it is still good.
   *
   * @return the number of sessions revoked — normally zero; non-zero only for an emergency
   *     rotation, or a graceful one retired before the session TTL elapsed
   */
  @Transactional
  public int retire(String kid) {
    keySetManager.retire(kid);
    return sessions.revokeAllForKey(kid, KEY_ROTATED_REASON, Instant.now());
  }
}
