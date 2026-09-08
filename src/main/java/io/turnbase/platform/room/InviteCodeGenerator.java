package io.turnbase.platform.room;

import java.security.SecureRandom;

/**
 * {@code room-rest-api.md} §2 — cryptographically random, not enumerable (NFR-SEC-002). 10
 * characters over a 62-symbol alphabet is ~59.5 bits of entropy, comfortably unguessable.
 */
final class InviteCodeGenerator {

  private static final String ALPHABET =
      "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
  private static final int LENGTH = 10;
  private static final SecureRandom RANDOM = new SecureRandom();

  private InviteCodeGenerator() {}

  static String generate() {
    StringBuilder code = new StringBuilder(LENGTH);
    for (int i = 0; i < LENGTH; i++) {
      code.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
    }
    return code.toString();
  }
}
