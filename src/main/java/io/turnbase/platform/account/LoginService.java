package io.turnbase.platform.account;

import io.turnbase.platform.common.Canonicalizer;
import io.turnbase.platform.common.LifecycleLog;
import io.turnbase.platform.common.UnauthenticatedException;
import io.turnbase.platform.session.SessionService;
import java.util.Locale;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * FR-AUTH-006: login by username or email. A failure at any step — no matching account, wrong
 * password — reports the same generic message, so a login attempt cannot be used to enumerate which
 * identifiers are registered.
 */
@Service
public class LoginService {

  private static final Logger log = LoggerFactory.getLogger(LoginService.class);

  private final UsernamePolicy usernamePolicy;
  private final PasswordEncoder passwordEncoder;
  private final AccountRepository accountRepository;
  private final SessionService sessionService;

  /**
   * NFR-SEC-001: verified against when no account matches, so an absent identifier costs the same
   * Argon2 work as a present one. Hashed once with the live encoder, so the cost parameters track
   * {@code PasswordEncoderConfig}.
   */
  private final String dummyHash;

  LoginService(
      UsernamePolicy usernamePolicy,
      PasswordEncoder passwordEncoder,
      AccountRepository accountRepository,
      SessionService sessionService) {
    this.usernamePolicy = usernamePolicy;
    this.passwordEncoder = passwordEncoder;
    this.accountRepository = accountRepository;
    this.sessionService = sessionService;
    this.dummyHash = passwordEncoder.encode("timing-equaliser-not-a-real-credential");
  }

  public LoginResult login(LoginRequest request, @Nullable String userAgentHash) {
    String canonicalIdentifier = Canonicalizer.canonicalize(request.usernameOrEmail());
    String skeleton = usernamePolicy.skeletonOfLoginIdentifier(canonicalIdentifier);
    String lowercasedEmail = canonicalIdentifier.toLowerCase(Locale.ROOT);

    // FR-AUTH-005/input-validation.md: the stored hash was computed from the canonicalized
    // password at registration, so verification must canonicalize the same way.
    String canonicalPassword = Canonicalizer.canonicalize(request.password());

    AccountRepository.Credential credential =
        accountRepository.findCredentialByIdentifier(skeleton, lowercasedEmail).orElse(null);
    if (credential == null) {
      passwordEncoder.matches(canonicalPassword, dummyHash);
      throw invalidCredentials();
    }
    if (!passwordEncoder.matches(canonicalPassword, credential.passwordHash())) {
      throw invalidCredentials();
    }

    SessionService.IssuedSession session =
        sessionService.issueSession(credential.userId(), userAgentHash);
    LifecycleLog.info(
        log, "User logged in", "userId", credential.userId(), "sessionId", session.sessionId());
    return new LoginResult(credential.userId(), credential.username(), session);
  }

  private static UnauthenticatedException invalidCredentials() {
    return new UnauthenticatedException("Invalid username/email or password.");
  }

  public record LoginResult(UUID userId, String username, SessionService.IssuedSession session) {}
}
