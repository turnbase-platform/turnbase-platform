package io.turnbase.platform.account;

import io.turnbase.platform.common.ConflictException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Plain JDBC against {@code app_user} / {@code user_auth_method} / {@code user_privacy_settings}.
 */
@Repository
class AccountRepository {

  private final JdbcClient jdbcClient;
  private final UsernamePolicy usernamePolicy;

  AccountRepository(JdbcClient jdbcClient, UsernamePolicy usernamePolicy) {
    this.jdbcClient = jdbcClient;
    this.usernamePolicy = usernamePolicy;
  }

  /**
   * {@code data-model-accounts.md} §1 flow D step 6: one transaction, three rows. All other columns
   * of {@code app_user} and {@code user_privacy_settings} carry their own database defaults.
   *
   * @throws ConflictException {@code username} or {@code email} collides with an existing account —
   *     distinguished by which unique index the database rejected, per {@code
   *     data-model-accounts.md} §1's registration flow note (a confusable collision reports
   *     identically to a plain duplicate, FR-AUTH-015 — both routes land on the same message here)
   */
  @Transactional
  void insertRegisteredUser(
      UUID userId, UsernamePolicy.ValidatedUsername username, String email, String passwordHash) {
    try {
      jdbcClient
          .sql(
              """
              insert into app_user (user_id, kind, username, username_skeleton, email, email_source)
              values (:userId, 'REGISTERED', :username, :usernameSkeleton, :email, 'SELF')
              """)
          .param("userId", userId)
          .param("username", username.display())
          .param("usernameSkeleton", username.skeleton())
          .param("email", email)
          .update();
    } catch (DataIntegrityViolationException e) {
      throw conflictFor(e);
    }

    jdbcClient
        .sql(
            """
            insert into user_auth_method (auth_method_id, user_id, method, password_hash)
            values (:authMethodId, :userId, 'PASSWORD', :passwordHash)
            """)
        .param("authMethodId", UUID.randomUUID())
        .param("userId", userId)
        .param("passwordHash", passwordHash)
        .update();

    jdbcClient
        .sql("insert into user_privacy_settings (user_id) values (:userId)")
        .param("userId", userId)
        .update();
  }

  /**
   * FR-AUTH-006: login by username or email, resolved in one query. {@code usernameSkeleton} and
   * {@code lowercasedEmail} are both derived from the same submitted identifier — an identifier
   * that happens to be neither a real username's skeleton nor a real email matches nothing, which
   * is exactly "no such account".
   */
  Optional<Credential> findCredentialByIdentifier(String usernameSkeleton, String lowercasedEmail) {
    return jdbcClient
        .sql(
            """
            select u.user_id, u.username, m.password_hash
            from app_user u
            join user_auth_method m on m.user_id = u.user_id and m.method = 'PASSWORD'
            where u.kind = 'REGISTERED' and u.deleted_at is null
              and (u.username_skeleton = :usernameSkeleton or lower(u.email) = :email)
            """)
        .param("usernameSkeleton", usernameSkeleton)
        .param("email", lowercasedEmail)
        .query(AccountRepository::mapCredential)
        .optional();
  }

  /**
   * FR-GUEST-001/002: mints a guest identity — {@code kind='GUEST'}, {@code guest-<serial>} drawn
   * from {@code guest_serial_seq}, no auth method row (FR-GUEST-004) and no privacy-settings row
   * (FR-GUEST-010's "only the username, regardless of viewer" is a display-layer rule with nothing
   * to configure, not a settings default). {@code data-model-accounts.md} §1: because the serial's
   * digits fold one-way onto letters, distinct serials always yield distinct skeletons, so a
   * unique-index violation on {@code app_user_skeleton_key} here can only mean that guarantee broke
   * — an assertion failure, not a retryable conflict.
   */
  @Transactional
  String insertGuestUser(UUID userId) {
    long serial = jdbcClient.sql("select nextval('guest_serial_seq')").query(Long.class).single();
    String username = "guest-" + serial;
    String skeleton = usernamePolicy.skeletonOfGuestUsername(username);
    try {
      jdbcClient
          .sql(
              """
              insert into app_user (user_id, kind, username, username_skeleton, guest_serial)
              values (:userId, 'GUEST', :username, :usernameSkeleton, :guestSerial)
              """)
          .param("userId", userId)
          .param("username", username)
          .param("usernameSkeleton", skeleton)
          .param("guestSerial", serial)
          .update();
    } catch (DataIntegrityViolationException e) {
      throw new IllegalStateException(
          "guest_serial "
              + serial
              + " produced a username_skeleton collision, which data-model-accounts.md §1 states"
              + " cannot happen for digit-only guest usernames",
          e);
    }
    return username;
  }

  /** {@code GET /api/auth/session} — the principal's display name, not stored on the JWT. */
  Optional<String> findUsernameById(UUID userId) {
    return jdbcClient
        .sql("select username from app_user where user_id = :userId")
        .param("userId", userId)
        .query(String.class)
        .optional();
  }

  /**
   * {@code oauth-rest-api.md} §4 step 3 — resolves a returning Google login by {@code sub}, never
   * by email ({@code data-model-accounts.md} §2).
   */
  Optional<Identity> findByGoogleSubject(String providerSubject) {
    return jdbcClient
        .sql(
            """
            select u.user_id, u.username
            from app_user u
            join user_auth_method m on m.user_id = u.user_id and m.method = 'GOOGLE'
            where u.deleted_at is null and m.provider_subject = :providerSubject
            """)
        .param("providerSubject", providerSubject)
        .query(AccountRepository::mapIdentity)
        .optional();
  }

  /**
   * {@code oauth-rest-api.md} §6 step 1 — whether {@code email} is already held by a live account,
   * for FR-AUTH-016's collision check. Only a {@code PASSWORD}-method account can collide this way
   * (data-model-accounts.md §2's {@code user_auth_method_subject_key} means a second Google account
   * can never share an email with an existing Google-linked one without also sharing its {@code
   * sub}, which §4 step 3 would have already matched).
   */
  Optional<Identity> findCredentialAccountByEmail(String lowercasedEmail) {
    return jdbcClient
        .sql(
            """
            select u.user_id, u.username
            from app_user u
            join user_auth_method m on m.user_id = u.user_id and m.method = 'PASSWORD'
            where u.kind = 'REGISTERED' and u.deleted_at is null and lower(u.email) = :email
            """)
        .param("email", lowercasedEmail)
        .query(AccountRepository::mapIdentity)
        .optional();
  }

  /** {@code oauth-rest-api.md} §7 — the password hash to challenge against. */
  Optional<String> findPasswordHashById(UUID userId) {
    return jdbcClient
        .sql(
            "select password_hash from user_auth_method where user_id = :userId and method ="
                + " 'PASSWORD'")
        .param("userId", userId)
        .query(String.class)
        .optional();
  }

  /**
   * {@code oauth-rest-api.md} §6 step 2 — mints a first-time Google account. The caller has already
   * confirmed the email is free ({@link #findCredentialAccountByEmail}); a unique-index violation
   * here can therefore only be the username skeleton, unlike {@link #insertRegisteredUser}'s
   * two-way check.
   */
  @Transactional
  void insertOAuthUser(
      UUID userId,
      UsernamePolicy.ValidatedUsername username,
      String email,
      String providerSubject) {
    try {
      jdbcClient
          .sql(
              """
              insert into app_user (user_id, kind, username, username_skeleton, email, email_source)
              values (:userId, 'REGISTERED', :username, :usernameSkeleton, :email, 'OAUTH')
              """)
          .param("userId", userId)
          .param("username", username.display())
          .param("usernameSkeleton", username.skeleton())
          .param("email", email)
          .update();
    } catch (DataIntegrityViolationException e) {
      throw new ConflictException("Username already taken.");
    }

    jdbcClient
        .sql(
            """
            insert into user_auth_method (auth_method_id, user_id, method, provider_subject)
            values (:authMethodId, :userId, 'GOOGLE', :providerSubject)
            """)
        .param("authMethodId", UUID.randomUUID())
        .param("userId", userId)
        .param("providerSubject", providerSubject)
        .update();

    jdbcClient
        .sql("insert into user_privacy_settings (user_id) values (:userId)")
        .param("userId", userId)
        .update();
  }

  /** {@code oauth-rest-api.md} §7 success path — FR-AUTH-009, an additional method. */
  void insertGoogleAuthMethod(UUID userId, String providerSubject) {
    jdbcClient
        .sql(
            """
            insert into user_auth_method (auth_method_id, user_id, method, provider_subject)
            values (:authMethodId, :userId, 'GOOGLE', :providerSubject)
            """)
        .param("authMethodId", UUID.randomUUID())
        .param("userId", userId)
        .param("providerSubject", providerSubject)
        .update();
  }

  private static Identity mapIdentity(ResultSet rs, int rowNum) throws SQLException {
    return new Identity(rs.getObject("user_id", UUID.class), rs.getString("username"));
  }

  record Identity(UUID userId, String username) {}

  private static Credential mapCredential(ResultSet rs, int rowNum) throws SQLException {
    return new Credential(
        rs.getObject("user_id", UUID.class),
        rs.getString("username"),
        rs.getString("password_hash"));
  }

  /**
   * Postgres reports a unique-index violation with the constraint's name in the error text (e.g.
   * {@code duplicate key value violates unique constraint "app_user_email_key"}); matching on that
   * substring distinguishes the two indexes without a compile-time dependency on the driver's own
   * exception type, which {@code conventions.md} §5 keeps at {@code runtime} scope.
   */
  private static ConflictException conflictFor(DataIntegrityViolationException e) {
    String message = String.valueOf(e.getMostSpecificCause().getMessage());
    if (message.contains("app_user_email_key")) {
      return new ConflictException("Email already registered.");
    }
    return new ConflictException("Username already taken.");
  }

  record Credential(UUID userId, String username, String passwordHash) {}
}
