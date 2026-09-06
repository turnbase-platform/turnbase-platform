package io.turnbase.platform.account;

import io.turnbase.platform.common.FieldValidationError;
import io.turnbase.platform.common.ValidationFailedException;
import io.turnbase.platform.session.SessionService;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * FR-AUTH-001: credential registration. {@code flows/01-arrival-and-account.md} §D — account
 * creation (steps 1-6, one transaction, {@link AccountRepository}) commits before session issuance
 * (step 7, a separate transaction) runs, per this repo's own commit-before-publish rule.
 */
@Service
public class RegistrationService {

  private final UsernamePolicy usernamePolicy;
  private final CredentialPolicy credentialPolicy;
  private final PasswordEncoder passwordEncoder;
  private final AccountRepository accountRepository;
  private final SessionService sessionService;

  RegistrationService(
      UsernamePolicy usernamePolicy,
      CredentialPolicy credentialPolicy,
      PasswordEncoder passwordEncoder,
      AccountRepository accountRepository,
      SessionService sessionService) {
    this.usernamePolicy = usernamePolicy;
    this.credentialPolicy = credentialPolicy;
    this.passwordEncoder = passwordEncoder;
    this.accountRepository = accountRepository;
    this.sessionService = sessionService;
  }

  public RegistrationResult register(RegisterRequest request, @Nullable String userAgentHash) {
    UsernamePolicy.ValidatedUsername username = usernamePolicy.validate(request.username());
    String email = credentialPolicy.validateEmail(request.email());
    String password = credentialPolicy.validatePassword(request.password());
    String confirmPassword = credentialPolicy.validatePassword(request.confirmPassword());
    if (!password.equals(confirmPassword)) {
      throw new ValidationFailedException(
          "Password failed validation.",
          List.of(new FieldValidationError("confirmPassword", "does not match password")));
    }

    UUID userId = UUID.randomUUID();
    String passwordHash = passwordEncoder.encode(password);
    accountRepository.insertRegisteredUser(userId, username, email, passwordHash);

    SessionService.IssuedSession session = sessionService.issueSession(userId, userAgentHash);
    return new RegistrationResult(userId, username.display(), session);
  }

  public record RegistrationResult(
      UUID userId, String username, SessionService.IssuedSession session) {}
}
