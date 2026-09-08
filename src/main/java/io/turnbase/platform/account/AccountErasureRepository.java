package io.turnbase.platform.account;

import io.turnbase.platform.session.SessionRevokedEvent;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code data-model-accounts.md} §5's erasure disposition table, plus {@code data-model-catalog.md}
 * §5, {@code data-model-history.md} §4, {@code data-model-notifications.md} §2 and {@code
 * data-model-social.md} §§1–2 — the documents that own the non-account rows §5 disposes of. One
 * {@code @Transactional} method, in the exact order §5 states: every step above the last is a
 * delete or a conditional update whose second execution matches zero rows, so a client retry after
 * a failure re-runs the same, already-idempotent statements. {@code room_participant} is last, per
 * §5.1's "ordered last so a retry re-runs only the idempotent operations above it."
 *
 * <p>{@code user_session} is written here directly with a literal SQL {@code UPDATE} rather than
 * through {@code session.SessionService}, which manages its own transaction boundary and would
 * either break this method's one-transaction requirement (a separate physical transaction) or, if
 * it silently joined this one, publish {@link SessionRevokedEvent} before this transaction actually
 * commits — this repo's commit-before-publish rule. The caller publishes it instead, once this
 * method's transaction has committed.
 */
@Repository
class AccountErasureRepository {

  private final JdbcClient jdbcClient;

  AccountErasureRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  /**
   * @param subjectToken a fresh random value, unique to this erasure and stored nowhere else —
   *     ADR-0004 forbids a derived token, which would be reversible by enumeration
   * @return the {@code session_id} of every session revoked, so the caller can publish {@link
   *     SessionRevokedEvent} for each once this transaction has committed
   */
  @Transactional
  List<UUID> erase(UUID userId, String subjectToken) {
    List<UUID> revokedSessionIds =
        jdbcClient
            .sql(
                """
                update user_session
                set revoked_at = now(), revoked_reason = 'ACCOUNT_DELETED'
                where user_id = :userId and revoked_at is null
                returning session_id
                """)
            .param("userId", userId)
            .query(UUID.class)
            .list();

    jdbcClient
        .sql("delete from user_auth_method where user_id = :userId")
        .param("userId", userId)
        .update();

    jdbcClient
        .sql("delete from user_privacy_settings where user_id = :userId")
        .param("userId", userId)
        .update();

    jdbcClient
        .sql("delete from friendship where user_id_low = :userId or user_id_high = :userId")
        .param("userId", userId)
        .update();

    jdbcClient
        .sql("delete from friend_request where sender_id = :userId or recipient_id = :userId")
        .param("userId", userId)
        .update();

    // Cascades the recipient's/inviter's notification for each row deleted here
    // (notification_invitation_fk ON DELETE CASCADE, data-model-notifications.md §2).
    jdbcClient
        .sql(
            "delete from room_invitation where inviter_user_id = :userId or invitee_user_id ="
                + " :userId")
        .param("userId", userId)
        .update();

    jdbcClient
        .sql("delete from notification where user_id = :userId")
        .param("userId", userId)
        .update();

    // FR-REV-... withdrawal: any undecided review on a game this user owns is closed as
    // WITHDRAWN, 'system' being the non-human decider (data-model-catalog.md §4/§5).
    jdbcClient
        .sql(
            """
            update game_review
            set decided_at = now(), decision = 'WITHDRAWN', decided_by = 'system'
            where decided_at is null
              and game_id in (select game_id from game where owner_user_id = :userId)
            """)
        .param("userId", userId)
        .update();

    // FR-ACCT-007 (amended, data-model-catalog.md §5): archival, not deletion — the catalog row
    // and its history-referencing versions are preserved, only the bundle artifacts are purged
    // later, once no non-closed room references them.
    jdbcClient
        .sql(
            "update game set archived_at = now() where owner_user_id = :userId and archived_at is null")
        .param("userId", userId)
        .update();

    // ADR-0004 / data-model-history.md §4: history rows survive, identity is severed. subject_token
    // is minted once by the caller and applied here in the same UPDATE across every entry.
    jdbcClient
        .sql(
            """
            update game_history_entry
            set user_id = null, subject_token = :subjectToken, pseudonymized_at = now()
            where user_id = :userId
            """)
        .param("userId", userId)
        .param("subjectToken", subjectToken)
        .update();

    // app_user (data-model-accounts.md §5): username/username_skeleton retained (FR-ACCT-006's
    // namespace reservation); email released for re-registration.
    jdbcClient
        .sql(
            """
            update app_user
            set deleted_at = now(), deletion_reason = 'SELF_SERVICE',
                email = null, email_source = null
            where user_id = :userId and deleted_at is null
            """)
        .param("userId", userId)
        .update();

    // room_participant last (data-model-accounts.md §5.1): a confirmed voluntary departure in
    // every non-closed room this user occupies. Closure/host-transfer is deliberately not done
    // here — room-lifecycle.md §4 requires the room's claim, which this REST-triggered transaction
    // does not hold; §5.1 duty 2 on the owning instance discharges it within one sweep interval.
    jdbcClient
        .sql(
            """
            update room_participant
            set left_at = now(), leave_reason = 'LEFT', lost_announced_at = null
            where user_id = :userId and left_at is null
            """)
        .param("userId", userId)
        .update();

    return revokedSessionIds;
  }
}
