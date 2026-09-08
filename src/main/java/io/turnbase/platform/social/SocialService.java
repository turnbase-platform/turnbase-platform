package io.turnbase.platform.social;

import io.turnbase.platform.common.BusinessRuleException;
import io.turnbase.platform.common.Canonicalizer;
import io.turnbase.platform.common.ForbiddenException;
import io.turnbase.platform.common.NotFoundException;
import io.turnbase.platform.common.RetentionProperties;
import io.turnbase.platform.common.UsernameSkeleton;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

/**
 * {@code social-rest-api.md} — send/accept/cancel/decline, the friend list, unfriend, and username
 * search. {@code data-model-social.md} §§1-3 for the transaction shapes {@link SocialRepository}
 * runs.
 */
@Service
class SocialService {

  private static final String KIND_GUEST_MESSAGE = "Guest accounts cannot use friends.";

  private final SocialRepository repository;
  private final SocialProperties socialProperties;
  private final RetentionProperties retentionProperties;

  SocialService(
      SocialRepository repository,
      SocialProperties socialProperties,
      RetentionProperties retentionProperties) {
    this.repository = repository;
    this.socialProperties = socialProperties;
    this.retentionProperties = retentionProperties;
  }

  Page<UserSearchResponse> search(UUID callerId, String rawSearch, Pageable pageable) {
    requireRegistered(callerId);
    String prefix = Canonicalizer.canonicalize(rawSearch).toLowerCase(Locale.ROOT);
    var rows =
        repository.search(prefix, callerId, pageable.getPageSize(), (int) pageable.getOffset());
    long total = repository.searchCount(prefix);
    var content =
        rows.stream()
            .map(
                row ->
                    new UserSearchResponse(
                        row.userId(), row.username(), row.profilePrivate(), row.canSendRequest()))
            .toList();
    return new PageImpl<>(content, pageable, total);
  }

  SendFriendRequestResponse sendRequest(UUID senderId, SendFriendRequestRequest request) {
    requireRegistered(senderId);
    String senderUsername = repository.findUsername(senderId);
    String skeleton = UsernameSkeleton.of(Canonicalizer.canonicalize(request.recipientUsername()));
    SocialRepository.RegisteredUser recipient =
        repository
            .findRegisteredUser(skeleton)
            .orElseThrow(() -> new NotFoundException("No such user."));
    if (recipient.userId().equals(senderId)) {
      throw new BusinessRuleException("You cannot send a friend request to yourself.");
    }

    SocialRepository.SendResult result =
        repository.sendRequest(
            senderId,
            senderUsername,
            recipient.userId(),
            recipient.username(),
            retentionProperties.friendRequest(),
            socialProperties.friendCap());

    return switch (result) {
      case SocialRepository.SendResult.RequestSent sent ->
          SendFriendRequestResponse.requestSent(
              new FriendRequestSummaryResponse(
                  sent.requestId(), recipient.username(), sent.createdAt(), sent.expiresAt()));
      case SocialRepository.SendResult.AutoAccepted accepted ->
          SendFriendRequestResponse.autoAccepted(
              new FriendSummaryResponse(accepted.friendUserId(), accepted.friendUsername()));
    };
  }

  Page<PendingRequestResponse> listPending(UUID callerId, Pageable pageable) {
    requireRegistered(callerId);
    var rows = repository.listPending(callerId, pageable.getPageSize(), (int) pageable.getOffset());
    long total = repository.listPendingCount(callerId);
    var content =
        rows.stream()
            .map(
                row ->
                    new PendingRequestResponse(
                        row.requestId(),
                        row.direction(),
                        row.counterpartUsername(),
                        row.createdAt(),
                        row.expiresAt()))
            .toList();
    return new PageImpl<>(content, pageable, total);
  }

  FriendSummaryResponse accept(UUID recipientId, UUID requestId) {
    requireRegistered(recipientId);
    SocialRepository.FriendSummary friend =
        repository.acceptRequest(recipientId, requestId, socialProperties.friendCap());
    return new FriendSummaryResponse(friend.userId(), friend.username());
  }

  void resolve(UUID callerId, UUID requestId) {
    requireRegistered(callerId);
    repository.resolveRequest(callerId, requestId);
  }

  Page<FriendSummaryResponse> friendsOf(
      String username, @Nullable UUID viewerId, Pageable pageable) {
    String skeleton = UsernameSkeleton.of(Canonicalizer.canonicalize(username));
    SocialRepository.FriendsListProfile profile =
        repository
            .findFriendsListProfile(skeleton)
            .orElseThrow(() -> new NotFoundException("No such user."));

    if (!isFriendsSectionVisible(profile, viewerId)) {
      return new PageImpl<>(List.of(), pageable, 0);
    }

    var rows =
        repository.listFriends(
            profile.userId(), pageable.getPageSize(), (int) pageable.getOffset());
    long total = repository.listFriendsCount(profile.userId());
    var content =
        rows.stream().map(row -> new FriendSummaryResponse(row.userId(), row.username())).toList();
    return new PageImpl<>(content, pageable, total);
  }

  void unfriend(UUID callerId, UUID otherUserId) {
    requireRegistered(callerId);
    repository.unfriend(callerId, otherUserId);
  }

  /**
   * FR-SOC-018 (owner exempt), FR-SOC-019 (a private profile hides the section from everyone else),
   * FR-SOC-017 (Public / Friends Only / Hidden). Deleted accounts and guests show nothing, matching
   * {@code account.ProfileResponse}'s deleted-placeholder and guest-only shapes, which carry no
   * {@code visibleSections} at all.
   */
  private boolean isFriendsSectionVisible(
      SocialRepository.FriendsListProfile profile, @Nullable UUID viewerId) {
    if (profile.deleted() || "GUEST".equals(profile.kind())) {
      return false;
    }
    if (viewerId != null && viewerId.equals(profile.userId())) {
      return true;
    }
    if (Boolean.TRUE.equals(profile.profilePrivate())) {
      return false;
    }
    String visibility = profile.friendsVisibility();
    if ("PUBLIC".equals(visibility)) {
      return true;
    }
    if ("FRIENDS_ONLY".equals(visibility)) {
      return viewerId != null && repository.friendshipExists(viewerId, profile.userId());
    }
    return false;
  }

  private void requireRegistered(UUID userId) {
    if (!repository.isRegistered(userId)) {
      throw new ForbiddenException(KIND_GUEST_MESSAGE);
    }
  }
}
