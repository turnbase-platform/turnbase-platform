package io.turnbase.platform.account;

import io.turnbase.platform.common.BusinessRuleException;
import io.turnbase.platform.common.FieldValidationError;
import io.turnbase.platform.common.ForbiddenException;
import io.turnbase.platform.common.NotFoundException;
import io.turnbase.platform.common.ValidationFailedException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * {@code user-accounts.md} FR-AUTH-010/011 — adding and removing the caller's own authentication
 * methods, {@code rest-conventions.md} § Authentication's {@code /api/users/me/auth-methods}
 * routes. FR-AUTH-012 holds by omission: nothing here writes {@code email} or, on an add, {@code
 * email_source}.
 */
@Service
public class AuthMethodService {

  private static final Set<String> REMOVABLE = Set.of("PASSWORD", "GOOGLE");

  private final AuthMethodRepository authMethodRepository;
  private final AccountRepository accountRepository;
  private final PendingGoogleLink pendingLink;
  private final CredentialPolicy credentialPolicy;
  private final PasswordEncoder passwordEncoder;

  AuthMethodService(
      AuthMethodRepository authMethodRepository,
      AccountRepository accountRepository,
      PendingGoogleLink pendingLink,
      CredentialPolicy credentialPolicy,
      PasswordEncoder passwordEncoder) {
    this.authMethodRepository = authMethodRepository;
    this.accountRepository = accountRepository;
    this.pendingLink = pendingLink;
    this.credentialPolicy = credentialPolicy;
    this.passwordEncoder = passwordEncoder;
  }

  /**
   * {@code POST .../auth-methods/password}, held to registration's password policy.
   *
   * @throws io.turnbase.platform.common.ConflictException a {@code PASSWORD} method is already
   *     linked
   */
  public void addPassword(UUID userId, AddPasswordRequest request) {
    requireRegistered(userId);
    String password = credentialPolicy.validatePassword(request.password());
    String confirmPassword = credentialPolicy.validatePassword(request.confirmPassword());
    if (!password.equals(confirmPassword)) {
      throw new ValidationFailedException(
          "Password failed validation.",
          List.of(new FieldValidationError("confirmPassword", "does not match password")));
    }
    authMethodRepository.insertPassword(userId, passwordEncoder.encode(password));
  }

  /**
   * {@code POST .../auth-methods/google} — {@code oauth-rest-api.md} §7a step 3, the request that
   * carries the session and therefore the one place ownership of the link record is checked.
   *
   * @throws NotFoundException no cookie, or no live record behind it
   * @throws ForbiddenException the record was minted for another user. It is left to expire rather
   *     than consumed, so a wrong caller cannot destroy someone else's pending link
   * @throws io.turnbase.platform.common.ConflictException a {@code GOOGLE} method is already
   *     linked, or another account won the race for this {@code sub}
   */
  public List<String> confirmGoogleLink(UUID userId, @Nullable String linkToken) {
    requireRegistered(userId);
    if (linkToken == null) {
      throw noLinkInProgress();
    }
    PendingGoogleLink.Record record =
        pendingLink.find(linkToken).orElseThrow(AuthMethodService::noLinkInProgress);
    if (!record.userId().equals(userId)) {
      throw new ForbiddenException("This link was not started by the current account.");
    }
    authMethodRepository.insertGoogle(userId, record.providerSubject());
    pendingLink.consume(linkToken);
    return authMethodRepository.findMethods(userId);
  }

  /**
   * {@code DELETE .../auth-methods/{method}}.
   *
   * @throws ValidationFailedException {@code method} is neither {@code PASSWORD} nor {@code GOOGLE}
   * @throws NotFoundException the caller has no such method
   * @throws BusinessRuleException it is the caller's only method (FR-AUTH-011)
   */
  public void remove(UUID userId, String method) {
    requireRegistered(userId);
    if (!REMOVABLE.contains(method)) {
      throw new ValidationFailedException(
          "Unknown authentication method.",
          List.of(new FieldValidationError("method", "must be one of " + REMOVABLE)));
    }
    switch (authMethodRepository.remove(userId, method)) {
      case REMOVED -> {}
      case ONLY_METHOD ->
          throw new BusinessRuleException(
              "An account must keep at least one authentication method.");
      case NOT_LINKED -> throw new NotFoundException("No such authentication method.");
    }
  }

  private void requireRegistered(UUID userId) {
    PrincipalResponse principal =
        accountRepository.findPrincipalById(userId).orElseThrow(AuthMethodService::noSuchAccount);
    if (principal.kind() != PrincipalResponse.Kind.REGISTERED) {
      throw new ForbiddenException("Guest accounts cannot manage authentication methods.");
    }
  }

  private static NotFoundException noLinkInProgress() {
    return new NotFoundException("No Google link in progress.");
  }

  private static IllegalStateException noSuchAccount() {
    return new IllegalStateException(
        "SessionAuthenticationFilter resolved a principal whose account no longer exists");
  }
}
