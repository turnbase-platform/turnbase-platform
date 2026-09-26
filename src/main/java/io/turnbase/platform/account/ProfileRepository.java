package io.turnbase.platform.account;

import io.turnbase.platform.common.ConflictException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code data-model-accounts.md} §1 (`app_user`) and §4 (`user_privacy_settings`) reads and writes
 * for the profile-read and self-scoped account-settings endpoints — {@code AccountRepository}
 * covers registration, login and guest minting instead.
 */
@Repository
class ProfileRepository {

  private final JdbcClient jdbcClient;

  ProfileRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  /** {@code GET /api/users/{username}}: the one query every branch of the read path starts from. */
  Optional<ProfileRow> findBySkeleton(String usernameSkeleton) {
    return jdbcClient
        .sql(
            """
            select u.user_id, u.username, u.kind, u.deleted_at,
                   p.profile_private, p.history_visibility, p.stats_visibility,
                   p.games_visibility, p.friends_visibility
            from app_user u
            left join user_privacy_settings p on p.user_id = u.user_id
            where u.username_skeleton = :usernameSkeleton
            """)
        .param("usernameSkeleton", usernameSkeleton)
        .query(ProfileRepository::mapProfileRow)
        .optional();
  }

  /** {@code GET /api/users/me}: the identity columns the account representation carries. */
  AccountRow findAccount(UUID userId) {
    return jdbcClient
        .sql(
            """
            select user_id, kind, username, email, email_source, username_changed_at,
                   email_changed_at
            from app_user
            where user_id = :userId
            """)
        .param("userId", userId)
        .query(
            (rs, rowNum) ->
                new AccountRow(
                    rs.getObject("user_id", UUID.class),
                    rs.getString("kind"),
                    rs.getString("username"),
                    rs.getString("email"),
                    rs.getString("email_source"),
                    toInstant(rs.getObject("username_changed_at", Timestamp.class)),
                    toInstant(rs.getObject("email_changed_at", Timestamp.class))))
        .single();
  }

  /** {@code PATCH /api/users/me}'s window check and OAuth-email-immutability guard. */
  AccountState findAccountState(UUID userId) {
    return jdbcClient
        .sql(
            """
            select kind, email_source, username_changed_at, email_changed_at
            from app_user
            where user_id = :userId
            """)
        .param("userId", userId)
        .query(ProfileRepository::mapAccountState)
        .single();
  }

  /**
   * Updates whichever of username/email is non-null, stamping only that field's {@code _changed_at}
   * column. {@code rest-conventions.md}: one endpoint, both fields independently windowed.
   *
   * @throws ConflictException the new username or email collides with an existing account
   */
  @Transactional
  IdentityRow updateIdentity(
      UUID userId, UsernamePolicy.@Nullable ValidatedUsername username, @Nullable String email) {
    try {
      return jdbcClient
          .sql(
              """
              update app_user set
                username = coalesce(:username::text, username),
                username_skeleton = coalesce(:usernameSkeleton::text, username_skeleton),
                username_changed_at = case when :username::text is not null then now()
                                           else username_changed_at end,
                email = coalesce(:email::text, email),
                email_changed_at = case when :email::text is not null then now()
                                        else email_changed_at end
              where user_id = :userId
              returning username, email
              """)
          .param("userId", userId)
          .param("username", username == null ? null : username.display())
          .param("usernameSkeleton", username == null ? null : username.skeleton())
          .param("email", email)
          .query(ProfileRepository::mapIdentityRow)
          .single();
    } catch (DataIntegrityViolationException e) {
      throw conflictFor(e);
    }
  }

  /**
   * {@code POST /api/users/me/password}: the credential row a registered user's password lives on.
   */
  Optional<String> findPasswordHash(UUID userId) {
    return jdbcClient
        .sql(
            "select password_hash from user_auth_method where user_id = :userId and method ="
                + " 'PASSWORD'")
        .param("userId", userId)
        .query(String.class)
        .optional();
  }

  @Transactional
  void updatePasswordHash(UUID userId, String newHash) {
    jdbcClient
        .sql(
            "update user_auth_method set password_hash = :hash, updated_at = now()"
                + " where user_id = :userId and method = 'PASSWORD'")
        .param("hash", newHash)
        .param("userId", userId)
        .update();
  }

  /**
   * {@code PATCH /api/users/me/privacy}: a partial {@code user_privacy_settings} representation.
   */
  PrivacyRow findPrivacySettings(UUID userId) {
    return jdbcClient
        .sql(
            """
            select profile_private, history_visibility, stats_visibility, games_visibility,
                   friends_visibility, block_friend_requests
            from user_privacy_settings
            where user_id = :userId
            """)
        .param("userId", userId)
        .query(ProfileRepository::mapPrivacyRow)
        .single();
  }

  @Transactional
  PrivacyRow updatePrivacySettings(UUID userId, UpdatePrivacyRequest request) {
    return jdbcClient
        .sql(
            """
            update user_privacy_settings set
              profile_private = coalesce(:profilePrivate::boolean, profile_private),
              history_visibility = coalesce(:historyVisibility::text, history_visibility),
              stats_visibility = coalesce(:statsVisibility::text, stats_visibility),
              games_visibility = coalesce(:gamesVisibility::text, games_visibility),
              friends_visibility = coalesce(:friendsVisibility::text, friends_visibility),
              block_friend_requests = coalesce(:blockFriendRequests::boolean, block_friend_requests),
              updated_at = now()
            where user_id = :userId
            returning profile_private, history_visibility, stats_visibility, games_visibility,
                      friends_visibility, block_friend_requests
            """)
        .param("userId", userId)
        .param("profilePrivate", request.profilePrivate())
        .param("historyVisibility", request.historyVisibility())
        .param("statsVisibility", request.statsVisibility())
        .param("gamesVisibility", request.gamesVisibility())
        .param("friendsVisibility", request.friendsVisibility())
        .param("blockFriendRequests", request.blockFriendRequests())
        .query(ProfileRepository::mapPrivacyRow)
        .single();
  }

  private static ProfileRow mapProfileRow(ResultSet rs, int rowNum) throws SQLException {
    return new ProfileRow(
        rs.getObject("user_id", UUID.class),
        rs.getString("username"),
        rs.getString("kind"),
        toInstant(rs.getObject("deleted_at", Timestamp.class)),
        rs.getObject("profile_private", Boolean.class),
        rs.getString("history_visibility"),
        rs.getString("stats_visibility"),
        rs.getString("games_visibility"),
        rs.getString("friends_visibility"));
  }

  private static AccountState mapAccountState(ResultSet rs, int rowNum) throws SQLException {
    return new AccountState(
        rs.getString("kind"),
        rs.getString("email_source"),
        toInstant(rs.getObject("username_changed_at", Timestamp.class)),
        toInstant(rs.getObject("email_changed_at", Timestamp.class)));
  }

  private static IdentityRow mapIdentityRow(ResultSet rs, int rowNum) throws SQLException {
    return new IdentityRow(rs.getString("username"), rs.getString("email"));
  }

  private static PrivacyRow mapPrivacyRow(ResultSet rs, int rowNum) throws SQLException {
    return new PrivacyRow(
        rs.getBoolean("profile_private"),
        rs.getString("history_visibility"),
        rs.getString("stats_visibility"),
        rs.getString("games_visibility"),
        rs.getString("friends_visibility"),
        rs.getBoolean("block_friend_requests"));
  }

  private static @Nullable Instant toInstant(@Nullable Timestamp timestamp) {
    return timestamp == null ? null : timestamp.toInstant();
  }

  /** Same constraint-name sniffing as {@code AccountRepository.conflictFor} — see its note. */
  private static ConflictException conflictFor(DataIntegrityViolationException e) {
    String message = String.valueOf(e.getMostSpecificCause().getMessage());
    if (message.contains("app_user_email_key")) {
      return new ConflictException("Email already registered.");
    }
    return new ConflictException("Username already taken.");
  }

  record ProfileRow(
      UUID userId,
      String username,
      String kind,
      @Nullable Instant deletedAt,
      @Nullable Boolean profilePrivate,
      @Nullable String historyVisibility,
      @Nullable String statsVisibility,
      @Nullable String gamesVisibility,
      @Nullable String friendsVisibility) {}

  record AccountState(
      String kind,
      @Nullable String emailSource,
      @Nullable Instant usernameChangedAt,
      @Nullable Instant emailChangedAt) {}

  record AccountRow(
      UUID userId,
      String kind,
      String username,
      @Nullable String email,
      @Nullable String emailSource,
      @Nullable Instant usernameChangedAt,
      @Nullable Instant emailChangedAt) {}

  record IdentityRow(String username, @Nullable String email) {}

  record PrivacyRow(
      boolean profilePrivate,
      String historyVisibility,
      String statsVisibility,
      String gamesVisibility,
      String friendsVisibility,
      boolean blockFriendRequests) {}
}
