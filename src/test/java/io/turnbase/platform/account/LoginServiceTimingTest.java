package io.turnbase.platform.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.turnbase.platform.common.UnauthenticatedException;
import io.turnbase.platform.session.SessionService;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

/** FR-AUTH-006 / NFR-SEC-001: an unknown account costs the same Argon2 work as a known one. */
class LoginServiceTimingTest {

  private final PasswordEncoder encoder = new PasswordEncoderConfig().passwordEncoder();
  private final AccountRepository accounts = mock(AccountRepository.class);
  private final SessionService sessions = mock(SessionService.class);
  private final LoginService service =
      new LoginService(new UsernamePolicy(), encoder, accounts, sessions);

  private static final String PASSWORD = "correct horse battery staple";

  LoginServiceTimingTest() {
    String hash = encoder.encode(PASSWORD);
    when(accounts.findCredentialByIdentifier(any(), any())).thenReturn(Optional.empty());
    when(accounts.findCredentialByIdentifier(
            org.mockito.ArgumentMatchers.eq(
                new UsernamePolicy().skeletonOfLoginIdentifier("alice")),
            any()))
        .thenReturn(
            Optional.of(new AccountRepository.Credential(UUID.randomUUID(), "alice", hash)));
  }

  private long medianNanos(String identifier) {
    List<Long> samples = new ArrayList<>();
    for (int i = 0; i < 9; i++) {
      long start = System.nanoTime();
      assertThatThrownBy(
              () -> service.login(new LoginRequest(identifier, "wrong-password-1"), null))
          .isInstanceOf(UnauthenticatedException.class);
      samples.add(System.nanoTime() - start);
    }
    Collections.sort(samples);
    return samples.get(samples.size() / 2);
  }

  @Test
  void unknownAndKnownAccountsHaveOverlappingLatencyAndIdenticalMessage() {
    // warm-up: JIT and the lazily-built dummy hash must not skew the first samples
    medianNanos("alice");
    medianNanos("nobody");

    double known = medianNanos("alice");
    double unknown = medianNanos("nobody");

    assertThat(unknown / known).isBetween(0.5, 2.0);

    Throwable present = catchLogin("alice");
    Throwable absent = catchLogin("nobody");
    assertThat(absent.getMessage()).isEqualTo(present.getMessage());
  }

  private Throwable catchLogin(String identifier) {
    try {
      service.login(new LoginRequest(identifier, "wrong-password-1"), null);
    } catch (UnauthenticatedException e) {
      return e;
    }
    throw new AssertionError("login unexpectedly succeeded");
  }
}
