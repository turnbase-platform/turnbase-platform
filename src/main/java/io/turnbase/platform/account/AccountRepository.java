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

  AccountRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
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
