package io.turnbase.platform.account;

/**
 * The single-field body of the two non-{@link PrincipalResponse} OAuth outcomes: {@code
 * oauth-rest-api.md} §6's {@code CHALLENGE_REQUIRED} and §7's {@code LINKED}.
 */
record OAuthOutcomeResponse(String outcome) {

  static final String CHALLENGE_REQUIRED = "CHALLENGE_REQUIRED";
  static final String LINKED = "LINKED";
}
