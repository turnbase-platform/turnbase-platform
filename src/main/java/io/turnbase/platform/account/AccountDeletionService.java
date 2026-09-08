package io.turnbase.platform.account;

import io.turnbase.platform.common.ForbiddenException;
import io.turnbase.platform.session.SessionRevokedEvent;
import java.util.List;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/**
 * {@code DELETE /api/users/me}. {@code user-accounts.md} FR-ACCT-004…008, ADR-0004 — irreversible
 * erasure via pseudonymization, not FR-ACCT-009/010, which {@code IMP-M1-07} already built
 * (IMP-GATE-32).
 */
@Service
class AccountDeletionService {

  private static final String KIND_REGISTERED = "REGISTERED";
  static final String ACCOUNT_DELETED_REASON = "ACCOUNT_DELETED";

  private final ProfileRepository profileRepository;
  private final AccountErasureRepository erasureRepository;
  private final ApplicationEventPublisher eventPublisher;

  AccountDeletionService(
      ProfileRepository profileRepository,
      AccountErasureRepository erasureRepository,
      ApplicationEventPublisher eventPublisher) {
    this.profileRepository = profileRepository;
    this.erasureRepository = erasureRepository;
    this.eventPublisher = eventPublisher;
  }

  /**
   * Guests are rejected the same way as every other {@code /me/**} mutation this repo builds
   * ({@code ProfileService.requireRegistered}): no {@code user_auth_method} row to strip and no
   * {@code user_privacy_settings} row to delete.
   *
   * <p>Publishes {@link SessionRevokedEvent} for every session the erasure revoked, once its
   * transaction has committed — this repo's commit-before-publish rule, same ordering as {@code
   * session.SessionService}.
   */
  void deleteAccount(UUID userId) {
    ProfileRepository.AccountState state = profileRepository.findAccountState(userId);
    if (!KIND_REGISTERED.equals(state.kind())) {
      throw new ForbiddenException("Guest accounts cannot be deleted this way.");
    }

    String subjectToken = UUID.randomUUID().toString();
    List<UUID> revokedSessionIds = erasureRepository.erase(userId, subjectToken);
    for (UUID sessionId : revokedSessionIds) {
      eventPublisher.publishEvent(
          new SessionRevokedEvent(sessionId, userId, ACCOUNT_DELETED_REASON));
    }
  }
}
