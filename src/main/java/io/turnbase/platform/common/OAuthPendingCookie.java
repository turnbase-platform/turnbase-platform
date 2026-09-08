package io.turnbase.platform.common;

/**
 * {@code oauth-rest-api.md} §2 — the pending-registration cookie's name, a fixed protocol constant
 * the same way {@link SessionCookie} is: {@code /auth/callback} sets it and three JSON endpoints
 * read it back, so it lives here rather than in whichever of those happens to set it first.
 */
public final class OAuthPendingCookie {

  public static final String NAME = "turnbase_oauth_pending";

  private OAuthPendingCookie() {}
}
