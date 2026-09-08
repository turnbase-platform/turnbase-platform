package io.turnbase.platform.common;

/**
 * {@code platform-views.md} VW-REDIR-005/006 — "a valid internal platform path", open-redirect
 * prevention. A candidate destination is accepted only if it can never be interpreted as pointing
 * off-origin: it must start with a single {@code /} and carry no scheme or authority of its own.
 */
public final class InternalRedirectPath {

  private InternalRedirectPath() {}

  /**
   * @return {@code candidate} if it is a valid internal path, otherwise {@code fallback}
   *     (VW-REDIR-006)
   */
  public static String validateOrFallback(String candidate, String fallback) {
    if (candidate.isEmpty()
        || candidate.charAt(0) != '/'
        || candidate.startsWith("//")
        || candidate.contains(":")) {
      return fallback;
    }
    return candidate;
  }
}
