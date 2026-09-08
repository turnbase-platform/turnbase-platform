package io.turnbase.platform.account;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * {@code GET /api/users/{username}}. Shape depends on which of FR-ACCT-009, FR-GUEST-010 or the
 * privacy-resolved registered case applies — {@code @JsonInclude(NON_NULL)} is what lets one record
 * serve all three without a wrapper the client has to unwrap, and is also the mechanism behind the
 * Done-when clause's "omits hidden sections rather than returning them for the client to hide":
 * {@link #visibleSections} names only what the viewer may see, so a hidden section is an absent
 * array entry, not a {@code false} the client is trusted to respect.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
record ProfileResponse(
    String username,
    @Nullable UUID userId,
    @Nullable Boolean deleted,
    @Nullable String kind,
    @Nullable Boolean profilePrivate,
    @Nullable Set<String> visibleSections) {

  static final String SECTION_GAME_HISTORY = "gameHistory";
  static final String SECTION_STATISTICS = "statistics";
  static final String SECTION_FRIENDS = "friends";
  static final String SECTION_UPLOADED_GAMES = "uploadedGames";

  static final Set<String> ALL_SECTIONS =
      Set.of(SECTION_GAME_HISTORY, SECTION_STATISTICS, SECTION_FRIENDS, SECTION_UPLOADED_GAMES);

  /** FR-ACCT-009: "the reserved username and a message... no other information is shown." */
  static ProfileResponse deletedPlaceholder(String username) {
    return new ProfileResponse(username, null, true, null, null, null);
  }

  /** FR-GUEST-010 / FR-PROF-002: "only the username... regardless of who is viewing it." */
  static ProfileResponse guestOnly(String username) {
    return new ProfileResponse(username, null, null, null, null, null);
  }

  static ProfileResponse registered(
      UUID userId, String username, boolean profilePrivate, Set<String> visibleSections) {
    return new ProfileResponse(
        username, userId, null, "REGISTERED", profilePrivate, visibleSections);
  }
}
