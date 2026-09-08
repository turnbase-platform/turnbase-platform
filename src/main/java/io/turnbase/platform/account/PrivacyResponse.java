package io.turnbase.platform.account;

/** {@code PATCH /api/users/me/privacy}'s {@code 200} body — the full resulting settings row. */
record PrivacyResponse(
    boolean profilePrivate,
    String historyVisibility,
    String statsVisibility,
    String gamesVisibility,
    String friendsVisibility,
    boolean blockFriendRequests) {

  static PrivacyResponse from(ProfileRepository.PrivacyRow row) {
    return new PrivacyResponse(
        row.profilePrivate(),
        row.historyVisibility(),
        row.statsVisibility(),
        row.gamesVisibility(),
        row.friendsVisibility(),
        row.blockFriendRequests());
  }
}
