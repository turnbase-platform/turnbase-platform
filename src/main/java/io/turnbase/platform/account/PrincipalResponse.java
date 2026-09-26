package io.turnbase.platform.account;

import java.util.UUID;

/**
 * {@code rest-conventions.md} § Authentication, "the principal representation" (IMP-GATE-75): the
 * body of {@code /api/auth/register}, {@code login}, {@code guest}, {@code GET /api/auth/session},
 * and {@code /api/auth/oauth/complete-registration}. {@code kind} is what the shell's tier check
 * reads — it must not have to infer the tier from a {@code guest-} username.
 */
record PrincipalResponse(UUID userId, String username, Kind kind) {

  /** The literal values of {@code app_user.kind} ({@code data-model-accounts.md} §1). */
  enum Kind {
    REGISTERED,
    GUEST
  }

  static PrincipalResponse registered(UUID userId, String username) {
    return new PrincipalResponse(userId, username, Kind.REGISTERED);
  }

  static PrincipalResponse guest(UUID userId, String username) {
    return new PrincipalResponse(userId, username, Kind.GUEST);
  }
}
