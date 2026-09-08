package io.turnbase.platform.account;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The two queries behind {@code data-model-accounts.md} §1's two-phase guest purge (FR-GUEST-005,
 * {@code platform-operations.md} FR-RET-002). Kept separate from {@link AccountRepository}, which
 * owns the create/read paths a request handler calls; this one is read only by {@link
 * GuestPurgeJob}.
 */
@Repository
class GuestPurgeRepository {

  private final JdbcClient jdbcClient;

  GuestPurgeRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  /**
   * Revoke-phase candidates: guests whose token lifetime has elapsed since creation, read over
   * {@code app_user_guest_expiry_idx}. Marking a row here does not by itself make the guest
   * inaccessible — the caller revokes its sessions (the actual enforcement point, {@code
   * SessionAuthenticationFilter} never consults {@code app_user}) before calling {@link
   * #markExpired}, in that order, so a crash between the two still leaves the row selectable
   * (`deleted_at` still null) on the job's next run.
   */
  List<UUID> findExpiredGuestUserIds(Instant cutoff) {
    return jdbcClient
        .sql(
            "select user_id from app_user where kind = 'GUEST' and deleted_at is null and"
                + " created_at < :cutoff")
        .param("cutoff", Timestamp.from(cutoff))
        .query(UUID.class)
        .list();
  }

  /**
   * Marks one guest inaccessible and enters it into the purge pool. {@code purgeable_after} is set
   * to {@code now}, not a future deadline — {@code data-model-accounts.md} §1's "the second phase
   * is not on a timer": eligibility from here on is gated only by {@link
   * #purgeUnreferencedGuests}'s referencing check.
   */
  void markExpired(UUID userId, Instant now) {
    jdbcClient
        .sql(
            "update app_user set deleted_at = :now, deletion_reason = 'GUEST_EXPIRED',"
                + " purgeable_after = :now where user_id = :userId")
        .param("now", Timestamp.from(now))
        .param("userId", userId)
        .update();
  }

  /**
   * Purge phase: hard-deletes every guest that is purgeable and referenced by neither hard foreign
   * key — {@code room_participant.user_id} nor {@code room.host_user_id} ({@code
   * data-model-accounts.md} §1). Cascades remove its {@code user_session} row(s); there is no
   * {@code user_privacy_settings} row to cascade, since guest minting never creates one.
   *
   * @return the ids actually purged, for the job's own log line
   */
  List<UUID> purgeUnreferencedGuests() {
    return jdbcClient
        .sql(
            """
            delete from app_user
            where kind = 'GUEST' and purgeable_after < now()
              and not exists (select 1 from room_participant rp where rp.user_id = app_user.user_id)
              and not exists (select 1 from room r where r.host_user_id = app_user.user_id)
            returning user_id
            """)
        .query(UUID.class)
        .list();
  }
}
