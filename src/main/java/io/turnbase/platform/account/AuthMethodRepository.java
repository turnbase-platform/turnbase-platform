package io.turnbase.platform.account;

import io.turnbase.platform.common.ConflictException;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code data-model-accounts.md} §2 — {@code user_auth_method} reads and writes for the settings
 * page's add/remove routes. {@code AccountRepository} covers the rows registration and the OAuth
 * flows create.
 */
@Repository
class AuthMethodRepository {

  static final String PASSWORD = "PASSWORD";
  static final String GOOGLE = "GOOGLE";

  private final JdbcClient jdbcClient;

  AuthMethodRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  /**
   * The {@code method} values only — {@code password_hash}/{@code provider_subject} never leave.
   */
  List<String> findMethods(UUID userId) {
    return jdbcClient
        .sql(
            "select method from user_auth_method where user_id = :userId"
                + " order by created_at, method")
        .param("userId", userId)
        .query(String.class)
        .list();
  }

  /**
   * @throws ConflictException the account already has a {@code PASSWORD} method — {@code
   *     user_auth_method_per_user_key}
   */
  @Transactional
  void insertPassword(UUID userId, String passwordHash) {
    try {
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
    } catch (DataIntegrityViolationException e) {
      throw new ConflictException("A password is already linked to this account.");
    }
  }

  /**
   * @throws ConflictException the account already has a {@code GOOGLE} method, or another account
   *     already holds this {@code sub} — {@code user_auth_method_per_user_key} / {@code
   *     user_auth_method_subject_key}; one message for both, so the response does not disclose
   *     which
   */
  @Transactional
  void insertGoogle(UUID userId, String providerSubject) {
    try {
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
    } catch (DataIntegrityViolationException e) {
      throw new ConflictException("This Google account cannot be linked.");
    }
  }

  /**
   * FR-AUTH-011's guarded delete ({@code data-model-accounts.md} §2), in one transaction with its
   * own count.
   *
   * <p>The guard alone is not enough. Under {@code READ COMMITTED} two removals of <em>different
   * rows</em> of a two-method account each see a count of two and each delete their own row — they
   * touch disjoint rows, so nothing makes the second wait. The {@code app_user} row lock taken
   * first is what serialises them: the second removal blocks until the first commits, then counts
   * one. {@code FOR NO KEY UPDATE}, not {@code FOR UPDATE}, so a concurrent session insert (which
   * takes {@code FOR KEY SHARE} on the same row through its foreign key) is not held up.
   *
   * <p>Removing {@code GOOGLE} from an {@code email_source = 'OAUTH'} account flips it to {@code
   * 'SELF'} in the same transaction (FR-AUTH-008): the address stops being bound to a provider the
   * account no longer uses.
   */
  @Transactional
  RemoveOutcome remove(UUID userId, String method) {
    jdbcClient
        .sql("select 1 from app_user where user_id = :userId for no key update")
        .param("userId", userId)
        .query(Integer.class)
        .single();

    int deleted =
        jdbcClient
            .sql(
                """
                delete from user_auth_method
                where user_id = :userId and method = :method
                  and (select count(*) from user_auth_method where user_id = :userId) > 1
                """)
            .param("userId", userId)
            .param("method", method)
            .update();
    if (deleted == 0) {
      boolean held =
          jdbcClient
              .sql(
                  "select exists(select 1 from user_auth_method"
                      + " where user_id = :userId and method = :method)")
              .param("userId", userId)
              .param("method", method)
              .query(Boolean.class)
              .single();
      return held ? RemoveOutcome.ONLY_METHOD : RemoveOutcome.NOT_LINKED;
    }

    if (GOOGLE.equals(method)) {
      jdbcClient
          .sql(
              "update app_user set email_source = 'SELF'"
                  + " where user_id = :userId and email_source = 'OAUTH'")
          .param("userId", userId)
          .update();
    }
    return RemoveOutcome.REMOVED;
  }

  enum RemoveOutcome {
    REMOVED,
    ONLY_METHOD,
    NOT_LINKED
  }
}
