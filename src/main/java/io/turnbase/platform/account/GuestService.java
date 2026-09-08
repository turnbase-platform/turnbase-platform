package io.turnbase.platform.account;

import io.turnbase.platform.session.SessionService;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * FR-GUEST-001/003: mints a guest principal with no credential of its own — the session token
 * issued here is the only means of access an account of this kind ever has (FR-GUEST-004). Mirrors
 * {@link RegistrationService}'s insert-then-issue-session order for the same commit-before-publish
 * reason: {@code account_repository.insertGuestUser}'s transaction commits before {@link
 * SessionService#issueSession} runs its own.
 */
@Service
public class GuestService {

  private final AccountRepository accountRepository;
  private final SessionService sessionService;

  GuestService(AccountRepository accountRepository, SessionService sessionService) {
    this.accountRepository = accountRepository;
    this.sessionService = sessionService;
  }

  public GuestResult mint(@Nullable String userAgentHash) {
    UUID userId = UUID.randomUUID();
    String username = accountRepository.insertGuestUser(userId);
    SessionService.IssuedSession session = sessionService.issueSession(userId, userAgentHash);
    return new GuestResult(userId, username, session);
  }

  public record GuestResult(UUID userId, String username, SessionService.IssuedSession session) {}
}
