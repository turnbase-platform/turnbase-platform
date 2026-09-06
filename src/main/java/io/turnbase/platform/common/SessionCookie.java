package io.turnbase.platform.common;

/**
 * {@code rest-conventions.md} § Authentication — the session cookie's name, a fixed protocol
 * constant every server-side reader (the STOMP handshake, the SSE endpoint, any authenticated-route
 * filter) must agree on with whoever sets it, hence living here rather than in whichever feature
 * happens to set it first.
 */
public final class SessionCookie {

  public static final String NAME = "turnbase_session";

  private SessionCookie() {}
}
