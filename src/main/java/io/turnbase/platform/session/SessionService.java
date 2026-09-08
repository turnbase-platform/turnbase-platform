package io.turnbase.platform.session;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
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
  public static final String LOGOUT_REASON = "LOGOUT";

  /**
   * FR-GUEST-005's revoke phase, and {@code app_user.deletion_reason}'s matching value ({@code
   * data-model-accounts.md} §1) — the same event, recorded in two tables under the one literal the
   * baseline migration's column comments both name.
   */
  public static final String GUEST_EXPIRED_REASON = "GUEST_EXPIRED";

  private static final int MAX_ATTEMPTS = 5;

  private final UserSessionRepository sessions;
  private final JwtService jwtService;
  private final AuthProperties authProperties;
  private final TransactionTemplate transactionTemplate;
  private final ApplicationEventPublisher eventPublisher;

  SessionService(
      UserSessionRepository sessions,
      JwtService jwtService,
      AuthProperties authProperties,
      PlatformTransactionManager transactionManager,
      ApplicationEventPublisher eventPublisher) {
    this.sessions = sessions;
    this.jwtService = jwtService;
    this.authProperties = authProperties;
    this.transactionTemplate = new TransactionTemplate(transactionManager);
    this.eventPublisher = eventPublisher;
  }

  /**
   * Displaces any session already active for {@code userId} and issues a new one (FR-SESS-001/002/
   * 003). {@code userAgentHash} is optional context for {@code user_session.user_agent_hash}; no
   * document in this item's read-list specifies how it is derived, so callers that have nothing to
   * offer pass {@code null}.
   *
   * <p>Publishes {@link SessionRevokedEvent} for whatever session was displaced, but only after the
   * displace-then-insert transaction has committed — this repo's commit-before-publish rule, and
   * the reason a plain post-commit call is used here rather than a
   * {@code @TransactionalEventListener}: {@link TransactionTemplate#execute} has already returned a
   * committed result by the time this method reaches the publish call.
   */
  public IssuedSession issueSession(UUID userId, @Nullable String userAgentHash) {
    DataIntegrityViolationException lastFailure = null;
    for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
      try {
        IssueResult result = transactionTemplate.execute(status -> issue(userId, userAgentHash));
        if (result != null) {
          for (UUID revokedSessionId : result.displacedSessionIds()) {
            eventPublisher.publishEvent(
                new SessionRevokedEvent(revokedSessionId, userId, DISPLACED_REASON));
          }
          return result.issued();
        }
      } catch (DataIntegrityViolationException e) {
        lastFailure = e;
      }
    }
    throw new IllegalStateException(
        "Could not issue a session for " + userId + " after " + MAX_ATTEMPTS + " attempts",
        lastFailure);
  }

  /**
   * FR-SESS-011: revokes exactly {@code sessionId}, publishing {@link SessionRevokedEvent} after
   * the revoking transaction commits — same ordering as {@link #issueSession}. A no-op (no event)
   * if the session was already revoked, which keeps logout idempotent against a retried request.
   */
  public void revoke(UUID sessionId, UUID userId, String reason) {
    Boolean revoked = transactionTemplate.execute(status -> revokeById(sessionId, reason));
    if (Boolean.TRUE.equals(revoked)) {
      eventPublisher.publishEvent(new SessionRevokedEvent(sessionId, userId, reason));
    }
  }

  /**
   * FR-GUEST-005's revoke phase ({@code data-model-accounts.md} §1): revokes every session still
   * active for {@code userId} (normally at most one, per {@code user_session_active_key}) with
   * {@code reason}, publishing {@link SessionRevokedEvent} for each after the revoking transaction
   * commits — same ordering as {@link #issueSession} and {@link #revoke}. A no-op if none are
   * active, which keeps the caller's own scheduled sweep idempotent against a retried run.
   */
  public void revokeAllForUser(UUID userId, String reason) {
    List<UUID> revokedSessionIds =
        transactionTemplate.execute(
            status -> sessions.revokeActiveForUser(userId, reason, Instant.now()));
    if (revokedSessionIds != null) {
      for (UUID sessionId : revokedSessionIds) {
        eventPublisher.publishEvent(new SessionRevokedEvent(sessionId, userId, reason));
      }
    }
  }

  private boolean revokeById(UUID sessionId, String reason) {
    return sessions.revokeById(sessionId, reason, Instant.now());
  }

  private IssueResult issue(UUID userId, @Nullable String userAgentHash) {
    Instant now = Instant.now();
    List<UUID> displaced = sessions.revokeActiveForUser(userId, DISPLACED_REASON, now);

    UUID sessionId = UUID.randomUUID();
    Instant expiresAt = now.plus(authProperties.sessionTtl());
    String token = jwtService.issue(userId, sessionId, now, expiresAt);
    String keyId = jwtService.activeKeyId();
    sessions.insert(
        new UserSession(sessionId, userId, now, expiresAt, null, null, keyId, userAgentHash));

    return new IssueResult(new IssuedSession(sessionId, token, expiresAt), displaced);
  }

  public record IssuedSession(UUID sessionId, String token, Instant expiresAt) {}

  private record IssueResult(IssuedSession issued, List<UUID> displacedSessionIds) {}
}
