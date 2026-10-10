package io.turnbase.platform.seed;

import io.turnbase.platform.common.UsernameSkeleton;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/**
 * Direct SQL against another feature's tables — {@code local-environment.md} §6: every statement is
 * idempotent against a fixed UUID. Follows {@code admin.AdminGameRepository}'s precedent; no
 * feature exposes a seeding entry point (IMP-GATE-72).
 */
@Repository
class SeedRepository {

  private final JdbcClient jdbcClient;
  private final JsonMapper objectMapper;

  SeedRepository(JdbcClient jdbcClient, JsonMapper objectMapper) {
    this.jdbcClient = jdbcClient;
    this.objectMapper = objectMapper;
  }

  /** {@code username} must already be canonical — the fixed seed names are lower-case ASCII. */
  void insertUser(UUID userId, String username, String email, String passwordHash) {
    jdbcClient
        .sql(
            """
            insert into app_user (user_id, kind, username, username_skeleton, email, email_source)
            values (:userId, 'REGISTERED', :username, :skeleton, :email, 'SELF')
            on conflict do nothing
            """)
        .param("userId", userId)
        .param("username", username)
        .param("skeleton", UsernameSkeleton.of(username))
        .param("email", email)
        .update();
    jdbcClient
        .sql(
            """
            insert into user_auth_method (auth_method_id, user_id, method, password_hash)
            select :authMethodId, :userId, 'PASSWORD', :passwordHash
             where not exists (select 1 from user_auth_method
                                where user_id = :userId and method = 'PASSWORD')
            on conflict do nothing
            """)
        .param("authMethodId", deriveId(userId, "auth-method"))
        .param("userId", userId)
        .param("passwordHash", passwordHash)
        .update();
    // data-model-accounts.md §4: a registered user always has a privacy row; registration writes
    // it, so the seeder, which bypasses registration, must too.
    jdbcClient
        .sql(
            """
            insert into user_privacy_settings (user_id) values (:userId)
            on conflict do nothing
            """)
        .param("userId", userId)
        .update();
  }

  void insertFriendship(UUID a, UUID b) {
    UUID low = a.compareTo(b) < 0 ? a : b;
    UUID high = a.compareTo(b) < 0 ? b : a;
    jdbcClient
        .sql(
            """
            insert into friendship (user_id_low, user_id_high) values (:low, :high)
            on conflict do nothing
            """)
        .param("low", low)
        .param("high", high)
        .update();
  }

  void insertFriendRequest(UUID requestId, UUID senderId, UUID recipientId, Duration expiresIn) {
    jdbcClient
        .sql(
            """
            insert into friend_request (request_id, sender_id, recipient_id, expires_at)
            values (:requestId, :senderId, :recipientId, :expiresAt)
            on conflict do nothing
            """)
        .param("requestId", requestId)
        .param("senderId", senderId)
        .param("recipientId", recipientId)
        .param("expiresAt", Timestamp.from(Instant.now().plus(expiresIn)))
        .update();
  }

  /** Payload is the other party's {@code {userId, username}}, as {@code social} writes it. */
  void insertNotification(
      UUID notificationId,
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
            on conflict do nothing
            """)
        .param("notificationId", notificationId)
        .param("userId", userId)
        .param("type", type)
        .param(
            "payload", jsonOf(Map.of("userId", otherUserId.toString(), "username", otherUsername)))
        .param("friendRequestId", friendRequestId)
        .update();
  }

  void insertOfficialGame(UUID gameId) {
    jdbcClient
        .sql(
            """
            insert into game (game_id, owner_user_id, title, short_description, badge, listed)
            values (:gameId, null, '', '', 'OFFICIAL', true)
            on conflict do nothing
            """)
        .param("gameId", gameId)
        .update();
  }

  boolean hasActiveVersion(UUID gameId) {
    return jdbcClient
        .sql("select active_version_id is not null from game where game_id = :gameId")
        .param("gameId", gameId)
        .query(Boolean.class)
        .single();
  }

  /** A deterministic id derived from a seed row's own, for rows the spec gives no fixed id. */
  private static UUID deriveId(UUID base, String purpose) {
    return UUID.nameUUIDFromBytes(
        (base + ":" + purpose).getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  private String jsonOf(Map<String, Object> value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalStateException("Failed to serialize notification payload", e);
    }
  }
}
