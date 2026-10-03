package io.turnbase.platform.social;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.turnbase.platform.common.BusinessRuleException;
import io.turnbase.platform.common.ConflictException;
import io.turnbase.platform.common.ForbiddenException;
import io.turnbase.platform.common.NotFoundException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code data-model-social.md} §§1-3 (`friendship`, `friend_request`) and {@code
 * data-model-notifications.md} §1/§3 (the four {@code FRIEND_REQUEST*}/{@code FRIEND_ADDED}
 * notification writes this feature owns, per {@code IMP-GATE-34}). Direct SQL against {@code
 * app_user} and {@code notification} — tables owned by {@code account} and {@code notification}
 * respectively — follows the same precedent as {@code account.AccountErasureRepository}: Spring
 * Modulith's boundary is a Java dependency graph, not a schema ownership map.
 */
@Repository
class SocialRepository {

  private static final String TYPE_FRIEND_REQUEST = "FRIEND_REQUEST";
  private static final String TYPE_FRIEND_REQUEST_ACCEPTED = "FRIEND_REQUEST_ACCEPTED";
  private static final String TYPE_FRIEND_REQUEST_DECLINED = "FRIEND_REQUEST_DECLINED";
  private static final String TYPE_FRIEND_ADDED = "FRIEND_ADDED";

  private final JdbcClient jdbcClient;
  private final ObjectMapper objectMapper;

  SocialRepository(JdbcClient jdbcClient, ObjectMapper objectMapper) {
    this.jdbcClient = jdbcClient;
    this.objectMapper = objectMapper;
  }

  /** FR-SOC-020/FR-GUEST-007: every endpoint in this feature is registered-only. */
  boolean isRegistered(UUID userId) {
    String kind =
        jdbcClient
            .sql("select kind from app_user where user_id = :userId")
            .param("userId", userId)
            .query(String.class)
            .single();
    return "REGISTERED".equals(kind);
  }

  /**
   * The caller's own username, for the payload of a notification this feature writes about them.
   */
  String findUsername(UUID userId) {
    return jdbcClient
        .sql("select username from app_user where user_id = :userId")
        .param("userId", userId)
        .query(String.class)
        .single();
  }

  /**
   * The privacy inputs {@code GET /api/users/{username}/friends} needs — re-derived here rather
   * than called through {@code account.ProfileService}, which would make {@code account} and {@code
   * social} depend on each other and fail {@code ApplicationModules.verify()}. {@code
   * account.ProfileService} calls this feature's {@link RelationshipQueries} instead; the coupling
   * runs one way.
   */
  Optional<FriendsListProfile> findFriendsListProfile(String usernameSkeleton) {
    return jdbcClient
        .sql(
            """
            select u.user_id, u.kind, u.deleted_at, p.profile_private, p.friends_visibility
            from app_user u
            left join user_privacy_settings p on p.user_id = u.user_id
            where u.username_skeleton = :skeleton
            """)
        .param("skeleton", usernameSkeleton)
        .query(SocialRepository::mapFriendsListProfile)
        .optional();
  }

  Optional<RegisteredUser> findRegisteredUser(String usernameSkeleton) {
    return jdbcClient
        .sql(
            """
            select user_id, username from app_user
            where username_skeleton = :skeleton and kind = 'REGISTERED' and deleted_at is null
            """)
        .param("skeleton", usernameSkeleton)
        .query(SocialRepository::mapRegisteredUser)
        .optional();
  }

  boolean friendshipExists(UUID a, UUID b) {
    UUID[] ordered = canonicalOrder(a, b);
    Boolean exists =
        jdbcClient
            .sql(
                "select exists(select 1 from friendship where user_id_low = :low and"
                    + " user_id_high = :high)")
            .param("low", ordered[0])
            .param("high", ordered[1])
            .query(Boolean.class)
            .single();
    return Boolean.TRUE.equals(exists);
  }

  /** {@code viewer}'s pending request naming {@code other}, in whichever direction exists. */
  Optional<Direction> pendingDirection(UUID viewer, UUID other) {
    return jdbcClient
        .sql(
            """
            select sender_id from friend_request
            where (sender_id = :viewer and recipient_id = :other)
               or (sender_id = :other and recipient_id = :viewer)
            """)
        .param("viewer", viewer)
        .param("other", other)
        .query(UUID.class)
        .optional()
        .map(senderId -> senderId.equals(viewer) ? Direction.OUTGOING : Direction.INCOMING);
  }

  int friendCount(UUID userId) {
    Long count =
        jdbcClient
            .sql(
                "select count(*) from friendship where user_id_low = :userId or user_id_high ="
                    + " :userId")
            .param("userId", userId)
            .query(Long.class)
            .single();
    return count == null ? 0 : count.intValue();
  }

  boolean blockFriendRequestsEnabled(UUID userId) {
    Boolean blocked =
        jdbcClient
            .sql("select block_friend_requests from user_privacy_settings where user_id = :userId")
            .param("userId", userId)
            .query(Boolean.class)
            .optional()
            .orElse(false);
    return Boolean.TRUE.equals(blocked);
  }

  List<SearchRow> search(String lowerPrefix, UUID callerId, int limit, int offset) {
    return jdbcClient
        .sql(
            """
            select u.user_id, u.username, coalesce(p.profile_private, false) as profile_private,
              ( u.user_id = :callerId
                or exists (select 1 from friendship f
                           where f.user_id_low = least(u.user_id, :callerId)
                             and f.user_id_high = greatest(u.user_id, :callerId))
                or exists (select 1 from friend_request fr
                           where (fr.sender_id = :callerId and fr.recipient_id = u.user_id)
                              or (fr.sender_id = u.user_id and fr.recipient_id = :callerId))
                or coalesce(p.block_friend_requests, false)
              ) as blocked
            from app_user u
            left join user_privacy_settings p on p.user_id = u.user_id
            where u.kind = 'REGISTERED' and u.deleted_at is null
              and lower(u.username) like :prefix || '%'
            order by lower(u.username) asc
            limit :limit offset :offset
            """)
        .param("callerId", callerId)
        .param("prefix", lowerPrefix)
        .param("limit", limit)
        .param("offset", offset)
        .query(SocialRepository::mapSearchRow)
        .list();
  }

  long searchCount(String lowerPrefix) {
    Long count =
        jdbcClient
            .sql(
                "select count(*) from app_user where kind = 'REGISTERED' and deleted_at is null"
                    + " and lower(username) like :prefix || '%'")
            .param("prefix", lowerPrefix)
            .query(Long.class)
            .single();
    return count == null ? 0 : count;
  }

  /** {@code social-rest-api.md} §3's evaluation order, all in one transaction. */
  @Transactional
  SendResult sendRequest(
      UUID senderId,
      String senderUsername,
      UUID recipientId,
      String recipientUsername,
      Duration expiryWindow,
      int friendCap) {
    if (friendshipExists(senderId, recipientId)) {
      throw new ConflictException("You are already friends with this user.");
    }
    if (pendingOutgoingExists(senderId, recipientId)) {
      throw new ConflictException("A friend request to this user is already pending.");
    }

    Optional<UUID> reverseRequestId = pendingRequestId(recipientId, senderId);
    if (reverseRequestId.isPresent()) {
      jdbcClient
          .sql("delete from friend_request where request_id = :requestId")
          .param("requestId", reverseRequestId.get())
          .update();
      insertFriendship(senderId, recipientId);
      insertNotification(recipientId, TYPE_FRIEND_REQUEST_ACCEPTED, null, senderId, senderUsername);
      insertNotification(senderId, TYPE_FRIEND_ADDED, null, recipientId, recipientUsername);
      return new SendResult.AutoAccepted(recipientId, recipientUsername);
    }

    if (friendCount(senderId) >= friendCap) {
      throw new BusinessRuleException("You have reached the maximum number of friends.");
    }
    if (blockFriendRequestsEnabled(recipientId)) {
      // FR-SOC-021: rejected without disclosing that a block exists — same message shape as any
      // other business-rule rejection on this endpoint.
      throw new BusinessRuleException("This friend request could not be sent.");
    }

    UUID requestId = UUID.randomUUID();
    Instant expiresAt = Instant.now().plus(expiryWindow);
    try {
      Instant createdAt =
          jdbcClient
              .sql(
                  """
                  insert into friend_request (request_id, sender_id, recipient_id, expires_at)
                  values (:requestId, :senderId, :recipientId, :expiresAt)
                  returning created_at
                  """)
              .param("requestId", requestId)
              .param("senderId", senderId)
              .param("recipientId", recipientId)
              .param("expiresAt", Timestamp.from(expiresAt))
              .query((rs, rowNum) -> requireNonNull(rs.getObject("created_at", Timestamp.class)))
              .single()
              .toInstant();
      insertNotification(recipientId, TYPE_FRIEND_REQUEST, requestId, senderId, senderUsername);
      return new SendResult.RequestSent(requestId, createdAt, expiresAt);
    } catch (DataIntegrityViolationException e) {
      throw new ConflictException("A friend request to this user is already pending.");
    }
  }

  /** {@code social-rest-api.md} §5. */
  @Transactional
  FriendSummary acceptRequest(UUID recipientId, UUID requestId, int friendCap) {
    FriendRequestRow row = findRequestOrThrow(requestId);
    if (!row.recipientId().equals(recipientId)) {
      throw ForbiddenSide.of(row, recipientId, "accept");
    }
    if (friendCount(recipientId) >= friendCap) {
      throw new BusinessRuleException("You have reached the maximum number of friends.");
    }

    jdbcClient
        .sql("delete from friend_request where request_id = :requestId")
        .param("requestId", requestId)
        .update();
    insertFriendship(row.senderId(), row.recipientId());
    insertNotification(
        row.senderId(),
        TYPE_FRIEND_REQUEST_ACCEPTED,
        null,
        row.recipientId(),
        row.recipientUsername());
    insertNotification(
        row.recipientId(), TYPE_FRIEND_ADDED, null, row.senderId(), row.senderUsername());
    return new FriendSummary(row.senderId(), row.senderUsername());
  }

  /**
   * {@code social-rest-api.md} §6: idempotent, {@code 403} only when the row names neither side.
   */
  @Transactional
  void resolveRequest(UUID callerId, UUID requestId) {
    Optional<FriendRequestRow> maybeRow = findRequest(requestId);
    if (maybeRow.isEmpty()) {
      return;
    }
    FriendRequestRow row = maybeRow.get();
    if (row.senderId().equals(callerId)) {
      jdbcClient
          .sql("delete from friend_request where request_id = :requestId")
          .param("requestId", requestId)
          .update();
      return;
    }
    if (row.recipientId().equals(callerId)) {
      jdbcClient
          .sql("delete from friend_request where request_id = :requestId")
          .param("requestId", requestId)
          .update();
      insertNotification(
          row.senderId(),
          TYPE_FRIEND_REQUEST_DECLINED,
          null,
          row.recipientId(),
          row.recipientUsername());
      return;
    }
    throw ForbiddenSide.of(row, callerId, "cancel or decline");
  }

  List<PendingRow> listPending(UUID callerId, int limit, int offset) {
    return jdbcClient
        .sql(
            """
            select fr.request_id, fr.created_at, fr.expires_at,
              case when fr.sender_id = :callerId then 'OUTGOING' else 'INCOMING' end as direction,
              case when fr.sender_id = :callerId then rcpt.username else sndr.username end
                as counterpart_username
            from friend_request fr
            join app_user sndr on sndr.user_id = fr.sender_id
            join app_user rcpt on rcpt.user_id = fr.recipient_id
            where fr.sender_id = :callerId or fr.recipient_id = :callerId
            order by fr.created_at desc
            limit :limit offset :offset
            """)
        .param("callerId", callerId)
        .param("limit", limit)
        .param("offset", offset)
        .query(SocialRepository::mapPendingRow)
        .list();
  }

  long listPendingCount(UUID callerId) {
    Long count =
        jdbcClient
            .sql(
                "select count(*) from friend_request where sender_id = :callerId or recipient_id ="
                    + " :callerId")
            .param("callerId", callerId)
            .query(Long.class)
            .single();
    return count == null ? 0 : count;
  }

  List<FriendRow> listFriends(UUID profileUserId, int limit, int offset) {
    return jdbcClient
        .sql(
            """
            select friend_id, friend_username
            from (select case when f.user_id_low = :profileUserId then hi.user_id else lo.user_id end
                           as friend_id,
                         case when f.user_id_low = :profileUserId then hi.username else lo.username end
                           as friend_username
                  from friendship f
                  join app_user lo on lo.user_id = f.user_id_low
                  join app_user hi on hi.user_id = f.user_id_high
                  where f.user_id_low = :profileUserId or f.user_id_high = :profileUserId) friends
            order by lower(friend_username) asc, friend_id asc
            limit :limit offset :offset
            """)
        .param("profileUserId", profileUserId)
        .param("limit", limit)
        .param("offset", offset)
        .query(SocialRepository::mapFriendRow)
        .list();
  }

  long listFriendsCount(UUID profileUserId) {
    return friendCount(profileUserId);
  }

  @Transactional
  void unfriend(UUID callerId, UUID otherUserId) {
    UUID[] ordered = canonicalOrder(callerId, otherUserId);
    jdbcClient
        .sql("delete from friendship where user_id_low = :low and user_id_high = :high")
        .param("low", ordered[0])
        .param("high", ordered[1])
        .update();
  }

  private boolean pendingOutgoingExists(UUID senderId, UUID recipientId) {
    Boolean exists =
        jdbcClient
            .sql(
                "select exists(select 1 from friend_request where sender_id = :senderId and"
                    + " recipient_id = :recipientId)")
            .param("senderId", senderId)
            .param("recipientId", recipientId)
            .query(Boolean.class)
            .single();
    return Boolean.TRUE.equals(exists);
  }

  private Optional<UUID> pendingRequestId(UUID senderId, UUID recipientId) {
    return jdbcClient
        .sql(
            "select request_id from friend_request where sender_id = :senderId and recipient_id ="
                + " :recipientId")
        .param("senderId", senderId)
        .param("recipientId", recipientId)
        .query(UUID.class)
        .optional();
  }

  private FriendRequestRow findRequestOrThrow(UUID requestId) {
    return findRequest(requestId)
        .orElseThrow(() -> new NotFoundException("No such friend request."));
  }

  private Optional<FriendRequestRow> findRequest(UUID requestId) {
    return jdbcClient
        .sql(
            """
            select fr.sender_id, fr.recipient_id, sndr.username as sender_username,
                   rcpt.username as recipient_username
            from friend_request fr
            join app_user sndr on sndr.user_id = fr.sender_id
            join app_user rcpt on rcpt.user_id = fr.recipient_id
            where fr.request_id = :requestId
            """)
        .param("requestId", requestId)
        .query(SocialRepository::mapFriendRequestRow)
        .optional();
  }

  private void insertFriendship(UUID a, UUID b) {
    UUID[] ordered = canonicalOrder(a, b);
    jdbcClient
        .sql("insert into friendship (user_id_low, user_id_high) values (:low, :high)")
        .param("low", ordered[0])
        .param("high", ordered[1])
        .update();
  }

  private void insertNotification(
      UUID userId,
      String type,
      @Nullable UUID friendRequestId,
      UUID otherUserId,
      String otherUsername) {
    jdbcClient
        .sql(
            """
            insert into notification (notification_id, user_id, type, payload, friend_request_id)
            values (:notificationId, :userId, :type, :payload::jsonb, :friendRequestId)
            """)
        .param("notificationId", UUID.randomUUID())
        .param("userId", userId)
        .param("type", type)
        .param(
            "payload", jsonOf(Map.of("userId", otherUserId.toString(), "username", otherUsername)))
        .param("friendRequestId", friendRequestId)
        .update();
  }

  /**
   * {@code postgresql} is a runtime-scoped dependency ({@code conventions.md} §1), so this writes
   * the JSON as text and casts it in SQL ({@code ::jsonb} above) rather than binding a driver-typed
   * {@code PGobject}, which would need it at compile time.
   */
  private String jsonOf(Map<String, Object> value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalStateException("Failed to serialize notification payload", e);
    }
  }

  /**
   * {@link UUID#compareTo} is signed-long order; Postgres's {@code uuid} is unsigned-byte order —
   * comparing the canonical string form matches the database exactly (the fix session 33's {@code
   * AccountDeletionControllerIT} found, reused here).
   */
  private static UUID[] canonicalOrder(UUID a, UUID b) {
    return a.toString().compareTo(b.toString()) < 0 ? new UUID[] {a, b} : new UUID[] {b, a};
  }

  private static <T> T requireNonNull(@Nullable T value) {
    if (value == null) {
      throw new IllegalStateException("Expected column value was null");
    }
    return value;
  }

  private static RegisteredUser mapRegisteredUser(ResultSet rs, int rowNum) throws SQLException {
    return new RegisteredUser(rs.getObject("user_id", UUID.class), rs.getString("username"));
  }

  private static FriendsListProfile mapFriendsListProfile(ResultSet rs, int rowNum)
      throws SQLException {
    return new FriendsListProfile(
        rs.getObject("user_id", UUID.class),
        rs.getString("kind"),
        rs.getObject("deleted_at", Timestamp.class) != null,
        rs.getObject("profile_private", Boolean.class),
        rs.getString("friends_visibility"));
  }

  private static SearchRow mapSearchRow(ResultSet rs, int rowNum) throws SQLException {
    return new SearchRow(
        rs.getObject("user_id", UUID.class),
        rs.getString("username"),
        rs.getBoolean("profile_private"),
        !rs.getBoolean("blocked"));
  }

  private static PendingRow mapPendingRow(ResultSet rs, int rowNum) throws SQLException {
    return new PendingRow(
        rs.getObject("request_id", UUID.class),
        rs.getString("direction"),
        rs.getString("counterpart_username"),
        requireNonNull(toInstant(rs.getObject("created_at", Timestamp.class))),
        requireNonNull(toInstant(rs.getObject("expires_at", Timestamp.class))));
  }

  private static FriendRow mapFriendRow(ResultSet rs, int rowNum) throws SQLException {
    return new FriendRow(rs.getObject("friend_id", UUID.class), rs.getString("friend_username"));
  }

  private static FriendRequestRow mapFriendRequestRow(ResultSet rs, int rowNum)
      throws SQLException {
    return new FriendRequestRow(
        rs.getObject("sender_id", UUID.class),
        rs.getObject("recipient_id", UUID.class),
        rs.getString("sender_username"),
        rs.getString("recipient_username"));
  }

  private static @Nullable Instant toInstant(@Nullable Timestamp timestamp) {
    return timestamp == null ? null : timestamp.toInstant();
  }

  enum Direction {
    OUTGOING,
    INCOMING
  }

  record RegisteredUser(UUID userId, String username) {}

  record FriendsListProfile(
      UUID userId,
      String kind,
      boolean deleted,
      @Nullable Boolean profilePrivate,
      @Nullable String friendsVisibility) {}

  record SearchRow(UUID userId, String username, boolean profilePrivate, boolean canSendRequest) {}

  record PendingRow(
      UUID requestId,
      String direction,
      String counterpartUsername,
      Instant createdAt,
      Instant expiresAt) {}

  record FriendRow(UUID userId, String username) {}

  record FriendSummary(UUID userId, String username) {}

  private record FriendRequestRow(
      UUID senderId, UUID recipientId, String senderUsername, String recipientUsername) {}

  sealed interface SendResult {
    record RequestSent(UUID requestId, Instant createdAt, Instant expiresAt)
        implements SendResult {}

    record AutoAccepted(UUID friendUserId, String friendUsername) implements SendResult {}
  }

  /**
   * {@code rest-conventions.md}'s "{@code principal ∈ {sender, recipient}}, and the action must
   * match the side" — a row exists but the caller is on neither side, or is on the wrong one for
   * this specific action.
   */
  private static final class ForbiddenSide {
    private ForbiddenSide() {}

    static ForbiddenException of(FriendRequestRow row, UUID callerId, String action) {
      boolean isParty = row.senderId().equals(callerId) || row.recipientId().equals(callerId);
      String message =
          isParty
              ? "You may not " + action + " this friend request."
              : "This friend request does not belong to you.";
      return new ForbiddenException(message);
    }
  }
}
