package io.turnbase.platform.common;

/**
 * {@code oauth-rest-api.md} §7a — the settings-page Google-link cookie's name, a fixed protocol
 * constant like {@link OAuthPendingCookie}: {@code /auth/callback} sets it and {@code POST
 * /api/users/me/auth-methods/google} reads it back.
 */
public final class OAuthLinkCookie {

  public static final String NAME = "turnbase_oauth_link";

  private OAuthLinkCookie() {}
}
