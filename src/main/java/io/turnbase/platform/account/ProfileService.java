package io.turnbase.platform.account;

import io.turnbase.platform.common.BusinessRuleException;
import io.turnbase.platform.common.Canonicalizer;
import io.turnbase.platform.common.FieldValidationError;
import io.turnbase.platform.common.ForbiddenException;
import io.turnbase.platform.common.NotFoundException;
import io.turnbase.platform.common.ValidationFailedException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * {@code user-accounts.md} FR-PROF-001…008, FR-ACCT-002…003, FR-ACCT-009…010, FR-GUEST-010 —
 * profile reads and the self-scoped account-settings mutations, {@code data-model-accounts.md} §1,
 * §4.
 */
@Service
public class ProfileService {

  private static final String KIND_REGISTERED = "REGISTERED";
  private static final String KIND_GUEST = "GUEST";
  private static final String VISIBILITY_HIDDEN = "HIDDEN";
  private static final String VISIBILITY_PUBLIC = "PUBLIC";
  private static final Set<String> TWO_VALUED_VISIBILITY =
      Set.of(VISIBILITY_PUBLIC, VISIBILITY_HIDDEN);
  private static final Set<String> FRIENDS_VISIBILITY =
      Set.of(VISIBILITY_PUBLIC, "FRIENDS_ONLY", VISIBILITY_HIDDEN);

  private final ProfileRepository profileRepository;
  private final UsernamePolicy usernamePolicy;
  private final CredentialPolicy credentialPolicy;
  private final PasswordEncoder passwordEncoder;
  private final AccountProperties accountProperties;

  ProfileService(
      ProfileRepository profileRepository,
      UsernamePolicy usernamePolicy,
      CredentialPolicy credentialPolicy,
      PasswordEncoder passwordEncoder,
      AccountProperties accountProperties) {
    this.profileRepository = profileRepository;
    this.usernamePolicy = usernamePolicy;
    this.credentialPolicy = credentialPolicy;
    this.passwordEncoder = passwordEncoder;
    this.accountProperties = accountProperties;
  }

  /**
   * {@code GET /api/users/{username}}. {@code viewerId} is {@code null} for an unauthenticated or
   * guest caller — neither can ever be the profile owner, so the exemption below never applies to
   * them, which is all "owner" needs to mean here.
   */
  public ProfileResponse view(String rawUsername, @Nullable UUID viewerId) {
    String skeleton =
        usernamePolicy.skeletonOfLoginIdentifier(Canonicalizer.canonicalize(rawUsername));
    ProfileRepository.ProfileRow row =
        profileRepository.findBySkeleton(skeleton).orElseThrow(ProfileService::noSuchUser);

    if (row.deletedAt() != null) {
      return ProfileResponse.deletedPlaceholder(row.username());
    }
    if (KIND_GUEST.equals(row.kind())) {
      return ProfileResponse.guestOnly(row.username());
    }

    if (viewerId != null && viewerId.equals(row.userId())) {
      return ProfileResponse.registered(
          row.userId(),
          row.username(),
          requireNonNull(row.profilePrivate()),
          ProfileResponse.ALL_SECTIONS);
    }
    if (Boolean.TRUE.equals(row.profilePrivate())) {
      return ProfileResponse.registered(row.userId(), row.username(), true, Set.of());
    }
    return ProfileResponse.registered(row.userId(), row.username(), false, visibleSectionsFor(row));
  }

  /**
   * FR-ACCT-002/003: username and/or email, each against its own rolling window ({@code
   * data-model-accounts.md} §1's single timestamp per field).
   */
  public IdentityResponse updateIdentity(UUID userId, UpdateProfileRequest request) {
    ProfileRepository.AccountState state = requireRegistered(userId);

    UsernamePolicy.ValidatedUsername validatedUsername = null;
    if (request.username() != null) {
      validatedUsername = usernamePolicy.validate(request.username());
      requireWindowElapsed("username", state.usernameChangedAt());
    }

    String validatedEmail = null;
    if (request.email() != null) {
      if ("OAUTH".equals(state.emailSource())) {
        throw new BusinessRuleException(
            "Email is managed by your OAuth provider and cannot be changed.");
      }
      validatedEmail = credentialPolicy.validateEmail(request.email());
      requireWindowElapsed("email", state.emailChangedAt());
    }

    ProfileRepository.IdentityRow row =
        profileRepository.updateIdentity(userId, validatedUsername, validatedEmail);
    return new IdentityResponse(row.username(), requireNonNull(row.email()));
  }

  /** FR-ACCT-010. */
  public void changePassword(UUID userId, ChangePasswordRequest request) {
    requireRegistered(userId);
    String newPassword = credentialPolicy.validatePassword(request.newPassword());
    String confirmNewPassword = credentialPolicy.validatePassword(request.confirmNewPassword());
    if (!newPassword.equals(confirmNewPassword)) {
      throw new ValidationFailedException(
          "Password failed validation.",
          List.of(new FieldValidationError("confirmNewPassword", "does not match newPassword")));
    }

    String currentPasswordHash =
        profileRepository
            .findPasswordHash(userId)
            .orElseThrow(
                () ->
                    new BusinessRuleException(
                        "No password authentication method is linked to this account."));
    String canonicalCurrentPassword = Canonicalizer.canonicalize(request.currentPassword());
    if (!passwordEncoder.matches(canonicalCurrentPassword, currentPasswordHash)) {
      throw new BusinessRuleException("Current password is incorrect.");
    }

    profileRepository.updatePasswordHash(userId, passwordEncoder.encode(newPassword));
    // data-model-accounts.md §3 / FR-SESS-002: at most one session is ever active per account, and
    // it is the one making this request, so there is no "other session" left to revoke — see
    // RegistrationService's identical note on FR-SESS-002 being a formality today.
  }

  /** FR-PROF-004, FR-PROF-006, FR-PROF-008. */
  public PrivacyResponse updatePrivacySettings(UUID userId, UpdatePrivacyRequest request) {
    requireRegistered(userId);
    List<FieldValidationError> errors = new ArrayList<>();
    checkVisibility(
        request.historyVisibility(), "historyVisibility", TWO_VALUED_VISIBILITY, errors);
    checkVisibility(request.statsVisibility(), "statsVisibility", TWO_VALUED_VISIBILITY, errors);
    checkVisibility(request.gamesVisibility(), "gamesVisibility", TWO_VALUED_VISIBILITY, errors);
    checkVisibility(request.friendsVisibility(), "friendsVisibility", FRIENDS_VISIBILITY, errors);
    if (!errors.isEmpty()) {
      throw new ValidationFailedException("Privacy settings failed validation.", errors);
    }

    return PrivacyResponse.from(profileRepository.updatePrivacySettings(userId, request));
  }

  /**
   * The friends section's third state, FR-SOC-017's "Friends Only," needs a confirmed-friendship
   * check that belongs to IMP-M1-09 (depends on this item) and does not exist yet — so it resolves
   * conservatively as hidden, identical to {@code HIDDEN}, until that item lands.
   */
  private static Set<String> visibleSectionsFor(ProfileRepository.ProfileRow row) {
    Set<String> visible = new LinkedHashSet<>();
    if (!VISIBILITY_HIDDEN.equals(row.historyVisibility())) {
      visible.add(ProfileResponse.SECTION_GAME_HISTORY);
    }
    if (!VISIBILITY_HIDDEN.equals(row.statsVisibility())) {
      visible.add(ProfileResponse.SECTION_STATISTICS);
    }
    if (!VISIBILITY_HIDDEN.equals(row.gamesVisibility())) {
      visible.add(ProfileResponse.SECTION_UPLOADED_GAMES);
    }
    if (VISIBILITY_PUBLIC.equals(row.friendsVisibility())) {
      visible.add(ProfileResponse.SECTION_FRIENDS);
    }
    return visible;
  }

  /**
   * Guests have no auth method to change a password against and no {@code user_privacy_settings}
   * row to patch (data-model-accounts.md §4: "a row exists only for registered users") — the
   * self-scoped mutations this class owns are all "Account Management" (`user-accounts.md`), a
   * section the "Guest Accounts" section never extends.
   */
  private ProfileRepository.AccountState requireRegistered(UUID userId) {
    ProfileRepository.AccountState state = profileRepository.findAccountState(userId);
    if (!KIND_REGISTERED.equals(state.kind())) {
      throw new ForbiddenException("Guest accounts cannot manage profile settings.");
    }
    return state;
  }

  private void requireWindowElapsed(String field, @Nullable Instant lastChangedAt) {
    if (lastChangedAt == null) {
      return;
    }
    Instant eligibleAt = lastChangedAt.plus(accountProperties.renameWindow());
    if (Instant.now().isBefore(eligibleAt)) {
      throw new BusinessRuleException(
          "The "
              + field
              + " may only be changed once every "
              + accountProperties.renameWindow().toDays()
              + " days.");
    }
  }

  private static void checkVisibility(
      @Nullable String value,
      String field,
      Set<String> allowed,
      List<FieldValidationError> errors) {
    if (value != null && !allowed.contains(value)) {
      errors.add(new FieldValidationError(field, "must be one of " + allowed));
    }
  }

  private static NotFoundException noSuchUser() {
    return new NotFoundException("No such user.");
  }

  private static <T> T requireNonNull(@Nullable T value) {
    if (value == null) {
      throw new IllegalStateException(
          "user_privacy_settings row missing for a live registered user — data-model-accounts.md §4"
              + " states one always exists");
    }
    return value;
  }
}
