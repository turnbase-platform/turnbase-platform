package io.turnbase.platform.seed;

import java.time.Duration;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * {@code local-environment.md} §6's Users and Notifications rows: four accounts, {@code
 * alice}↔{@code bob} friends, {@code carol}'s request to {@code alice} pending, and the four
 * notification types the running system can produce, as one story. Fixed UUIDs throughout, so a
 * second start changes nothing.
 */
@Component
class SocialSeed {

  static final UUID ALICE = fixed(0xa1);
  static final UUID BOB = fixed(0xa2);
  static final UUID CAROL = fixed(0xa3);
  static final UUID DAVE = fixed(0xa4);

  static final UUID CAROL_TO_ALICE_REQUEST = fixed(0xb1);

  private static final String PASSWORD = "password";
  private static final Duration REQUEST_EXPIRY = Duration.ofDays(30);

  private final SeedRepository repository;
  private final PasswordEncoder passwordEncoder;

  SocialSeed(SeedRepository repository, PasswordEncoder passwordEncoder) {
    this.repository = repository;
    this.passwordEncoder = passwordEncoder;
  }

  void apply() {
    repository.insertUser(ALICE, "alice", "alice@example.test", passwordEncoder.encode(PASSWORD));
    repository.insertUser(BOB, "bob", "bob@example.test", passwordEncoder.encode(PASSWORD));
    repository.insertUser(CAROL, "carol", "carol@example.test", passwordEncoder.encode(PASSWORD));
    repository.insertUser(DAVE, "dave", "dave@example.test", passwordEncoder.encode(PASSWORD));

    repository.insertFriendship(ALICE, BOB);
    repository.insertFriendRequest(CAROL_TO_ALICE_REQUEST, CAROL, ALICE, REQUEST_EXPIRY);

    // carol's pending request, as alice sees it.
    repository.insertNotification(
        fixed(0xc1), ALICE, "FRIEND_REQUEST", CAROL_TO_ALICE_REQUEST, CAROL, "carol");
    // bob's request, accepted by alice: bob is told, alice gets the confirmation.
    repository.insertNotification(
        fixed(0xc2), BOB, "FRIEND_REQUEST_ACCEPTED", null, ALICE, "alice");
    repository.insertNotification(fixed(0xc3), ALICE, "FRIEND_ADDED", null, BOB, "bob");
    // dave's request to alice, declined: nothing survives but its notification.
    repository.insertNotification(
        fixed(0xc4), DAVE, "FRIEND_REQUEST_DECLINED", null, ALICE, "alice");
  }

  private static UUID fixed(int n) {
    return new UUID(0x7365656400000000L, n);
  }
}
