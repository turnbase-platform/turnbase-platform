package io.turnbase.platform.session;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Session issuance — the top-level API a registration or login flow calls once it has resolved a
 * principal, and the mechanics behind {@code data-model-accounts.md} §3's "only one active session
 * per account" invariant (FR-SESS-002).
 *
 * <p>The displace-then-insert is one transaction, per §3: revoke whatever session is currently
 * active for the user, then insert the new row. Two concurrent logins can both pass the revoke and
 * then race on {@code user_session_active_key}'s partial unique index at insert time — the loser's
 * transaction rolls back and retries, at which point the winner's row is what it revokes, so the
 * retry always succeeds within a small, bounded number of attempts.
 */
@Service
public class SessionService {

  static final String DISPLACED_REASON = "DISPLACED";
  private static final int MAX_ATTEMPTS = 5;

  private final UserSessionRepository sessions;
  private final JwtService jwtService;
  private final AuthProperties authProperties;
  private final TransactionTemplate transactionTemplate;

  SessionService(
      UserSessionRepository sessions,
      JwtService jwtService,
      AuthProperties authProperties,
      PlatformTransactionManager transactionManager) {
    this.sessions = sessions;
    this.jwtService = jwtService;
    this.authProperties = authProperties;
    this.transactionTemplate = new TransactionTemplate(transactionManager);
  }

  /**
   * Displaces any session already active for {@code userId} and issues a new one (FR-SESS-001/002/
   * 003). {@code userAgentHash} is optional context for {@code user_session.user_agent_hash}; no
   * document in this item's read-list specifies how it is derived, so callers that have nothing to
   * offer pass {@code null}.
   */
  public IssuedSession issueSession(UUID userId, @Nullable String userAgentHash) {
    DataIntegrityViolationException lastFailure = null;
    for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
      try {
        IssuedSession issued = transactionTemplate.execute(status -> issue(userId, userAgentHash));
        if (issued != null) {
          return issued;
        }
      } catch (DataIntegrityViolationException e) {
        lastFailure = e;
      }
    }
    throw new IllegalStateException(
        "Could not issue a session for " + userId + " after " + MAX_ATTEMPTS + " attempts",
        lastFailure);
  }

  private IssuedSession issue(UUID userId, @Nullable String userAgentHash) {
    Instant now = Instant.now();
    sessions.revokeActiveForUser(userId, DISPLACED_REASON, now);

    UUID sessionId = UUID.randomUUID();
    Instant expiresAt = now.plus(authProperties.sessionTtl());
    String token = jwtService.issue(userId, sessionId, now, expiresAt);
    String keyId = jwtService.activeKeyId();
    sessions.insert(
        new UserSession(sessionId, userId, now, expiresAt, null, null, keyId, userAgentHash));

    return new IssuedSession(sessionId, token, expiresAt);
  }

  public record IssuedSession(UUID sessionId, String token, Instant expiresAt) {}
}
