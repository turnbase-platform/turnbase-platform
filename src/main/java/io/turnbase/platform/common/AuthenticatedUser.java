package io.turnbase.platform.common;

import java.security.Principal;
import java.util.UUID;

/**
 * The resolved identity of an authenticated request — {@code rest-conventions.md} §
 * Authentication's session-cookie principal, verified against {@code user_session} by {@code
 * IMP-M1-06}'s authentication filter. Lives here, not in {@code session}, because any feature's
 * controller needs to read "who is making this request" without depending on how the session was
 * resolved.
 */
public record AuthenticatedUser(UUID userId, UUID sessionId) implements Principal {

  @Override
  public String getName() {
    return userId.toString();
  }
}
