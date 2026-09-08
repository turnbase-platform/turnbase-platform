package io.turnbase.platform.social;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.jspecify.annotations.Nullable;

/**
 * {@code POST /api/friend-requests} — {@code social-rest-api.md} §3. Both outcomes are {@code 201};
 * {@code outcome} discriminates which of {@link #friendRequest} / {@link #friend} is populated, the
 * same {@code @JsonInclude(NON_NULL)} idiom {@code account.ProfileResponse} uses.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
record SendFriendRequestResponse(
    String outcome,
    @Nullable FriendRequestSummaryResponse friendRequest,
    @Nullable FriendSummaryResponse friend) {

  static final String OUTCOME_REQUEST_SENT = "REQUEST_SENT";
  static final String OUTCOME_FRIENDS = "FRIENDS";

  static SendFriendRequestResponse requestSent(FriendRequestSummaryResponse friendRequest) {
    return new SendFriendRequestResponse(OUTCOME_REQUEST_SENT, friendRequest, null);
  }

  static SendFriendRequestResponse autoAccepted(FriendSummaryResponse friend) {
    return new SendFriendRequestResponse(OUTCOME_FRIENDS, null, friend);
  }
}
