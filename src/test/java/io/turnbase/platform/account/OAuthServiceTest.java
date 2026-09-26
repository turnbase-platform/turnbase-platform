package io.turnbase.platform.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.turnbase.platform.common.ConflictException;
import io.turnbase.platform.common.NotFoundException;
import io.turnbase.platform.common.UnauthenticatedException;
import io.turnbase.platform.session.AuthProperties;
import io.turnbase.platform.session.JwtVerificationException;
import io.turnbase.platform.session.OAuthStateService;
import io.turnbase.platform.session.SessionService;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

/** {@code oauth-rest-api.md} — the orchestration unit tests, everything below it mocked. */
class OAuthServiceTest {

  private static final OAuthProperties CONFIGURED =
      new OAuthProperties(
          "client-id",
          "client-secret",
          "https://accounts.google.com/o/oauth2/v2/auth",
          "https://oauth2.googleapis.com/token",
          "https://openidconnect.googleapis.com/v1/userinfo");

  private final AuthProperties authProperties =
      new AuthProperties(
          Duration.ofHours(24),
          false,
          null,
          new AuthProperties.OAuth(Duration.ofMinutes(5), Duration.ofMinutes(10)));

  private final GoogleOAuthClient googleClient = mock(GoogleOAuthClient.class);
  private final OAuthStateService stateService = mock(OAuthStateService.class);
  private final PendingOAuthRegistration pending = mock(PendingOAuthRegistration.class);
  private final AccountRepository accountRepository = mock(AccountRepository.class);
  private final UsernamePolicy usernamePolicy = new UsernamePolicy();
  private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
  private final SessionService sessionService = mock(SessionService.class);
  private final PendingGoogleLink pendingLink = mock(PendingGoogleLink.class);

  private OAuthService serviceWith(OAuthProperties oauthProperties) {
    return new OAuthService(
        oauthProperties,
        authProperties,
        googleClient,
        stateService,
        pending,
        accountRepository,
        usernamePolicy,
        passwordEncoder,
        sessionService,
        pendingLink);
  }

  private final OAuthService service = serviceWith(CONFIGURED);

  // ---- initiate ----

  @Test
  void initiateFailsCleanlyWhenNotConfigured() {
    OAuthProperties unconfigured =
        new OAuthProperties(
            null,
            null,
            "https://accounts.google.com/o/oauth2/v2/auth",
            "https://oauth2.googleapis.com/token",
            "https://openidconnect.googleapis.com/v1/userinfo");

    assertThatThrownBy(() -> serviceWith(unconfigured).initiate(null))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void initiateValidatesTheRedirectAndDelegatesToGoogle() {
    when(stateService.issue(eq("/rooms/abcd"), any(), any())).thenReturn("signed-state");
    when(googleClient.authorizationUrl("signed-state"))
        .thenReturn(URI.create("https://accounts.google.com/o/oauth2/v2/auth?state=signed-state"));

    URI result = service.initiate("/rooms/abcd");

    assertThat(result.toString()).contains("state=signed-state");
  }

  @Test
  void initiateFallsBackToDefaultDestinationOnAnOpenRedirectAttempt() {
    when(stateService.issue(eq("/rooms"), any(), any())).thenReturn("signed-state");
    when(googleClient.authorizationUrl(anyString()))
        .thenReturn(URI.create("https://accounts.google.com/o/oauth2/v2/auth"));

    service.initiate("https://evil.example.com");

    verify(stateService).issue(eq("/rooms"), any(), any());
  }

  // ---- callback ----

  @Test
  void callbackReturnsErrorWhenCodeOrStateIsMissing() {
    assertThat(service.callback(null, "state"))
        .isInstanceOf(OAuthService.CallbackResult.Error.class);
    assertThat(service.callback("code", null))
        .isInstanceOf(OAuthService.CallbackResult.Error.class);
  }

  @Test
  void callbackReturnsErrorWhenStateFailsVerification() {
    when(stateService.verify("bad-state")).thenThrow(new JwtVerificationException("bad"));

    assertThat(service.callback("code", "bad-state"))
        .isInstanceOf(OAuthService.CallbackResult.Error.class);
  }

  @Test
  void callbackReturnsErrorWhenGoogleExchangeFails() {
    when(stateService.verify("state"))
        .thenReturn(new OAuthStateService.VerifiedState("/rooms", null));
    when(googleClient.exchangeCode("code"))
        .thenThrow(new GoogleOAuthClient.GoogleOAuthException("failed", null));

    assertThat(service.callback("code", "state"))
        .isInstanceOf(OAuthService.CallbackResult.Error.class);
  }

  @Test
  void callbackLogsInAReturningGoogleUser() {
    UUID userId = UUID.randomUUID();
    when(stateService.verify("state"))
        .thenReturn(new OAuthStateService.VerifiedState("/rooms/abcd", null));
    when(googleClient.exchangeCode("code"))
        .thenReturn(new GoogleOAuthClient.GoogleIdentity("sub-1", "alice@example.com"));
    when(accountRepository.findByGoogleSubject("sub-1"))
        .thenReturn(Optional.of(new AccountRepository.Identity(userId, "alice")));
    SessionService.IssuedSession session =
        new SessionService.IssuedSession(UUID.randomUUID(), "token", Instant.now());
    when(sessionService.issueSession(userId, null)).thenReturn(session);

    OAuthService.CallbackResult result = service.callback("code", "state");

    assertThat(result).isInstanceOf(OAuthService.CallbackResult.LoggedIn.class);
    var loggedIn = (OAuthService.CallbackResult.LoggedIn) result;
    assertThat(loggedIn.destination()).isEqualTo("/rooms/abcd");
    assertThat(loggedIn.session()).isEqualTo(session);
  }

  @Test
  void callbackStartsPendingRegistrationForAFirstTimeGoogleLogin() {
    when(stateService.verify("state"))
        .thenReturn(new OAuthStateService.VerifiedState("/rooms", null));
    when(googleClient.exchangeCode("code"))
        .thenReturn(new GoogleOAuthClient.GoogleIdentity("sub-1", "alice@example.com"));
    when(accountRepository.findByGoogleSubject("sub-1")).thenReturn(Optional.empty());
    when(pending.create("sub-1", "alice@example.com")).thenReturn("pending-token");
    when(pending.ttl()).thenReturn(Duration.ofMinutes(10));

    OAuthService.CallbackResult result = service.callback("code", "state");

    assertThat(result).isInstanceOf(OAuthService.CallbackResult.PendingRegistration.class);
    assertThat(((OAuthService.CallbackResult.PendingRegistration) result).token())
        .isEqualTo("pending-token");
  }

  @Test
  void linkCallbackStoresTheLinkRecordAndNeverIssuesOrReadsASession() {
    UUID userId = UUID.randomUUID();
    when(stateService.verify("state"))
        .thenReturn(new OAuthStateService.VerifiedState("/settings", userId));
    when(googleClient.exchangeCode("code"))
        .thenReturn(new GoogleOAuthClient.GoogleIdentity("sub-1", "alice@example.com"));
    when(accountRepository.googleSubjectHeld("sub-1")).thenReturn(false);
    when(pendingLink.create(userId, "sub-1")).thenReturn("link-token");
    when(pendingLink.ttl()).thenReturn(Duration.ofMinutes(10));

    OAuthService.CallbackResult result = service.callback("code", "state");

    assertThat(result).isInstanceOf(OAuthService.CallbackResult.LinkPending.class);
    assertThat(((OAuthService.CallbackResult.LinkPending) result).token()).isEqualTo("link-token");
    verify(sessionService, never()).issueSession(any(), any());
    verify(accountRepository, never()).findByGoogleSubject(anyString());
  }

  @Test
  void linkCallbackForASubAlreadyHeldStoresNothing() {
    UUID userId = UUID.randomUUID();
    when(stateService.verify("state"))
        .thenReturn(new OAuthStateService.VerifiedState("/settings", userId));
    when(googleClient.exchangeCode("code"))
        .thenReturn(new GoogleOAuthClient.GoogleIdentity("sub-1", "alice@example.com"));
    when(accountRepository.googleSubjectHeld("sub-1")).thenReturn(true);

    OAuthService.CallbackResult result = service.callback("code", "state");

    assertThat(result).isInstanceOf(OAuthService.CallbackResult.LinkError.class);
    verify(pendingLink, never()).create(any(), anyString());
  }

  // ---- readPendingEmail ----

  @Test
  void readPendingEmailFailsWithNoCookie() {
    assertThatThrownBy(() -> service.readPendingEmail(null)).isInstanceOf(NotFoundException.class);
  }

  @Test
  void readPendingEmailFailsWhenTheRecordHasExpired() {
    when(pending.find("tok")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.readPendingEmail("tok")).isInstanceOf(NotFoundException.class);
  }

  @Test
  void readPendingEmailReturnsTheStoredEmail() {
    when(pending.find("tok"))
        .thenReturn(
            Optional.of(new PendingOAuthRegistration.Record("sub-1", "alice@example.com", null)));

    assertThat(service.readPendingEmail("tok")).isEqualTo("alice@example.com");
  }

  // ---- completeRegistration ----

  @Test
  void completeRegistrationCreatesTheAccountWhenTheEmailIsFree() {
    UUID sessionId = UUID.randomUUID();
    when(pending.find("tok"))
        .thenReturn(
            Optional.of(new PendingOAuthRegistration.Record("sub-1", "alice@example.com", null)));
    when(accountRepository.findCredentialAccountByEmail("alice@example.com"))
        .thenReturn(Optional.empty());
    SessionService.IssuedSession session =
        new SessionService.IssuedSession(sessionId, "token", Instant.now());
    when(sessionService.issueSession(any(), eq(null))).thenReturn(session);

    OAuthService.CompleteRegistrationResult result = service.completeRegistration("tok", "alice");

    assertThat(result).isInstanceOf(OAuthService.CompleteRegistrationResult.Created.class);
    var created = (OAuthService.CompleteRegistrationResult.Created) result;
    assertThat(created.username()).isEqualTo("alice");
    verify(accountRepository)
        .insertOAuthUser(eq(created.userId()), any(), eq("alice@example.com"), eq("sub-1"));
    verify(pending).consume("tok");
  }

  @Test
  void completeRegistrationReportsAChallengeRequiredOnEmailCollisionWithoutCreatingAnAccount() {
    UUID collisionUserId = UUID.randomUUID();
    PendingOAuthRegistration.Record record =
        new PendingOAuthRegistration.Record("sub-1", "alice@example.com", null);
    when(pending.find("tok")).thenReturn(Optional.of(record));
    when(accountRepository.findCredentialAccountByEmail("alice@example.com"))
        .thenReturn(Optional.of(new AccountRepository.Identity(collisionUserId, "alice-existing")));

    OAuthService.CompleteRegistrationResult result = service.completeRegistration("tok", "alice");

    assertThat(result)
        .isInstanceOf(OAuthService.CompleteRegistrationResult.ChallengeRequired.class);
    verify(pending).markCollision("tok", record, collisionUserId);
    verify(accountRepository, never()).insertOAuthUser(any(), any(), anyString(), anyString());
    verify(pending, never()).consume(anyString());
  }

  @Test
  void completeRegistrationPropagatesAUsernameConflict() {
    when(pending.find("tok"))
        .thenReturn(
            Optional.of(new PendingOAuthRegistration.Record("sub-1", "alice@example.com", null)));
    when(accountRepository.findCredentialAccountByEmail("alice@example.com"))
        .thenReturn(Optional.empty());
    org.mockito.Mockito.doThrow(new ConflictException("Username already taken."))
        .when(accountRepository)
        .insertOAuthUser(any(), any(), anyString(), anyString());

    assertThatThrownBy(() -> service.completeRegistration("tok", "alice"))
        .isInstanceOf(ConflictException.class);
  }

  // ---- linkChallenge ----

  @Test
  void linkChallengeFailsWithNoCollisionOnTheRecord() {
    when(pending.find("tok"))
        .thenReturn(
            Optional.of(new PendingOAuthRegistration.Record("sub-1", "alice@example.com", null)));

    assertThatThrownBy(() -> service.linkChallenge("tok", "password"))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void linkChallengeRejectsAWrongPasswordWithAGenericFailure() {
    UUID collisionUserId = UUID.randomUUID();
    when(pending.find("tok"))
        .thenReturn(
            Optional.of(
                new PendingOAuthRegistration.Record(
                    "sub-1", "alice@example.com", collisionUserId)));
    when(accountRepository.findPasswordHashById(collisionUserId)).thenReturn(Optional.of("hash"));
    when(passwordEncoder.matches(anyString(), eq("hash"))).thenReturn(false);

    assertThatThrownBy(() -> service.linkChallenge("tok", "wrong"))
        .isInstanceOf(UnauthenticatedException.class);
    verify(accountRepository, never()).insertGoogleAuthMethod(any(), anyString());
  }

  @Test
  void linkChallengeLinksTheGoogleIdentityOnACorrectPassword() {
    UUID collisionUserId = UUID.randomUUID();
    when(pending.find("tok"))
        .thenReturn(
            Optional.of(
                new PendingOAuthRegistration.Record(
                    "sub-1", "alice@example.com", collisionUserId)));
    when(accountRepository.findPasswordHashById(collisionUserId)).thenReturn(Optional.of("hash"));
    when(passwordEncoder.matches(anyString(), eq("hash"))).thenReturn(true);
    SessionService.IssuedSession session =
        new SessionService.IssuedSession(UUID.randomUUID(), "token", Instant.now());
    when(sessionService.issueSession(collisionUserId, null)).thenReturn(session);

    SessionService.IssuedSession result = service.linkChallenge("tok", "correct");

    assertThat(result).isEqualTo(session);
    verify(accountRepository).insertGoogleAuthMethod(collisionUserId, "sub-1");
    verify(pending, times(1)).consume("tok");
  }
}
