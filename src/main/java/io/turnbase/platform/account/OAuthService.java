package io.turnbase.platform.account;

import io.turnbase.platform.common.Canonicalizer;
import io.turnbase.platform.common.InternalRedirectPath;
import io.turnbase.platform.common.NotFoundException;
import io.turnbase.platform.common.UnauthenticatedException;
import io.turnbase.platform.session.AuthProperties;
import io.turnbase.platform.session.JwtVerificationException;
import io.turnbase.platform.session.OAuthStateService;
import io.turnbase.platform.session.SessionService;
import java.net.URI;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * {@code oauth-rest-api.md} — orchestrates the five endpoints {@link OAuthController} and {@link
 * OAuthRegistrationController} expose. FR-AUTH-007/016.
 */
@Service
public class OAuthService {

  private static final String DEFAULT_DESTINATION = "/rooms";

  private final OAuthProperties oauthProperties;
  private final AuthProperties authProperties;
  private final GoogleOAuthClient googleClient;
  private final OAuthStateService stateService;
  private final PendingOAuthRegistration pending;
  private final AccountRepository accountRepository;
  private final UsernamePolicy usernamePolicy;
  private final PasswordEncoder passwordEncoder;
  private final SessionService sessionService;

  OAuthService(
      OAuthProperties oauthProperties,
      AuthProperties authProperties,
      GoogleOAuthClient googleClient,
      OAuthStateService stateService,
      PendingOAuthRegistration pending,
      AccountRepository accountRepository,
      UsernamePolicy usernamePolicy,
      PasswordEncoder passwordEncoder,
      SessionService sessionService) {
    this.oauthProperties = oauthProperties;
    this.authProperties = authProperties;
    this.googleClient = googleClient;
    this.stateService = stateService;
    this.pending = pending;
    this.accountRepository = accountRepository;
    this.usernamePolicy = usernamePolicy;
    this.passwordEncoder = passwordEncoder;
    this.sessionService = sessionService;
  }

  /**
   * §3. {@code redirect} is validated the way {@code VW-REDIR-005/006} validates the login flow's
   * own parameter of the same name — an internal path, or the open-redirect fallback.
   *
   * @throws NotFoundException Google OAuth is not configured ({@code secrets-and-configuration.md}
   *     §1) — the login/register pages never render a button that would reach this, but a direct
   *     hit must still fail cleanly rather than 500 on a blank client id
   */
  public URI initiate(@Nullable String redirect) {
    if (!oauthProperties.configured()) {
      throw new NotFoundException("Google OAuth is not configured.");
    }
    String destination =
        InternalRedirectPath.validateOrFallback(
            redirect == null ? "" : redirect, DEFAULT_DESTINATION);
    Instant now = Instant.now();
    String state =
        stateService.issue(destination, now, now.plus(authProperties.oauth().stateTtl()));
    return googleClient.authorizationUrl(state);
  }

  /** §4. Never throws for attacker-influenceable input — {@link CallbackResult#error()} instead. */
  public CallbackResult callback(@Nullable String code, @Nullable String state) {
    if (code == null || state == null) {
      return CallbackResult.error();
    }
    String destination;
    try {
      destination = stateService.verify(state);
    } catch (JwtVerificationException e) {
      return CallbackResult.error();
    }

    GoogleOAuthClient.GoogleIdentity identity;
    try {
      identity = googleClient.exchangeCode(code);
    } catch (GoogleOAuthClient.GoogleOAuthException e) {
      return CallbackResult.error();
    }

    Optional<AccountRepository.Identity> existing =
        accountRepository.findByGoogleSubject(identity.sub());
    if (existing.isPresent()) {
      SessionService.IssuedSession session =
          sessionService.issueSession(existing.get().userId(), null);
      return CallbackResult.loggedIn(session, destination);
    }

    String token = pending.create(identity.sub(), identity.email());
    return CallbackResult.pendingRegistration(token, Instant.now().plus(pending.ttl()));
  }

  /** §5. */
  public String readPendingEmail(@Nullable String pendingToken) {
    String token = requireToken(pendingToken);
    return pending.find(token).orElseThrow(OAuthService::noPendingRegistration).email();
  }

  /**
   * §6.
   *
   * @throws io.turnbase.platform.common.ValidationFailedException {@code username} fails {@code
   *     input-validation.md}'s pipeline
   * @throws io.turnbase.platform.common.ConflictException the username skeleton is already taken
   */
  public CompleteRegistrationResult completeRegistration(
      @Nullable String pendingToken, String rawUsername) {
    String token = requireToken(pendingToken);
    PendingOAuthRegistration.Record record =
        pending.find(token).orElseThrow(OAuthService::noPendingRegistration);

    String lowercasedEmail = record.email().toLowerCase(Locale.ROOT);
    Optional<AccountRepository.Identity> collision =
        accountRepository.findCredentialAccountByEmail(lowercasedEmail);
    if (collision.isPresent()) {
      pending.markCollision(token, record, collision.get().userId());
      return CompleteRegistrationResult.challengeRequired();
    }

    UsernamePolicy.ValidatedUsername username = usernamePolicy.validate(rawUsername);
    UUID userId = UUID.randomUUID();
    accountRepository.insertOAuthUser(userId, username, record.email(), record.providerSubject());
    pending.consume(token);

    SessionService.IssuedSession session = sessionService.issueSession(userId, null);
    return CompleteRegistrationResult.created(userId, username.display(), session);
  }

  /**
   * §7.
   *
   * @throws NotFoundException no pending record, or it names no collision to resolve
   * @throws UnauthenticatedException the password does not match — the generic FR-AUTH-016 failure
   */
  public SessionService.IssuedSession linkChallenge(
      @Nullable String pendingToken, String rawPassword) {
    String token = requireToken(pendingToken);
    PendingOAuthRegistration.Record record =
        pending.find(token).orElseThrow(OAuthService::noPendingRegistration);
    UUID collisionUserId = record.collisionUserId();
    if (collisionUserId == null) {
      throw new NotFoundException("No collision to resolve.");
    }

    String passwordHash =
        accountRepository
            .findPasswordHashById(collisionUserId)
            .orElseThrow(OAuthService::invalidChallenge);
    String canonicalPassword = Canonicalizer.canonicalize(rawPassword);
    if (!passwordEncoder.matches(canonicalPassword, passwordHash)) {
      throw invalidChallenge();
    }

    accountRepository.insertGoogleAuthMethod(collisionUserId, record.providerSubject());
    pending.consume(token);
    return sessionService.issueSession(collisionUserId, null);
  }

  private String requireToken(@Nullable String pendingToken) {
    if (pendingToken == null) {
      throw noPendingRegistration();
    }
    return pendingToken;
  }

  private static NotFoundException noPendingRegistration() {
    return new NotFoundException("No pending registration.");
  }

  private static UnauthenticatedException invalidChallenge() {
    return new UnauthenticatedException("Invalid credentials.");
  }

  public sealed interface CallbackResult {
    static CallbackResult error() {
      return new Error();
    }

    static CallbackResult loggedIn(SessionService.IssuedSession session, String destination) {
      return new LoggedIn(session, destination);
    }

    static CallbackResult pendingRegistration(String token, Instant expiresAt) {
      return new PendingRegistration(token, expiresAt);
    }

    record Error() implements CallbackResult {}

    record LoggedIn(SessionService.IssuedSession session, String destination)
        implements CallbackResult {}

    record PendingRegistration(String token, Instant expiresAt) implements CallbackResult {}
  }

  public sealed interface CompleteRegistrationResult {
    static CompleteRegistrationResult created(
        UUID userId, String username, SessionService.IssuedSession session) {
      return new Created(userId, username, session);
    }

    static CompleteRegistrationResult challengeRequired() {
      return new ChallengeRequired();
    }

    record Created(UUID userId, String username, SessionService.IssuedSession session)
        implements CompleteRegistrationResult {}

    record ChallengeRequired() implements CompleteRegistrationResult {}
  }
}
