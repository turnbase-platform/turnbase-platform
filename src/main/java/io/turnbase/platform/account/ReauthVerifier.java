package io.turnbase.platform.account;

import io.turnbase.platform.common.BusinessRuleException;
import io.turnbase.platform.common.Canonicalizer;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Server-side proof of the current password before a permanent change ({@code user-accounts.md}
 * FR-ACCT-003/004, {@code IMP-GATE-99}) — a session left open on a shared machine must not be able
 * to change the email or erase the account. An account with no password method (OAuth-only) has
 * nothing to verify against and is not asked.
 */
@Component
class ReauthVerifier {

  private final ProfileRepository profileRepository;
  private final PasswordEncoder passwordEncoder;

  ReauthVerifier(ProfileRepository profileRepository, PasswordEncoder passwordEncoder) {
    this.profileRepository = profileRepository;
    this.passwordEncoder = passwordEncoder;
  }

  /** Throws {@link BusinessRuleException} ({@code 422}) when the password is missing or wrong. */
  void require(UUID userId, @Nullable String currentPassword) {
    Optional<String> hash = profileRepository.findPasswordHash(userId);
    if (hash.isEmpty()) {
      return;
    }
    if (currentPassword == null || currentPassword.isBlank()) {
      throw new BusinessRuleException("The current password is required.");
    }
    if (!passwordEncoder.matches(Canonicalizer.canonicalize(currentPassword), hash.get())) {
      throw new BusinessRuleException("Current password is incorrect.");
    }
  }
}
