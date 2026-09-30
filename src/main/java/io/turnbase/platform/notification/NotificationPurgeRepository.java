package io.turnbase.platform.notification;

import java.time.Duration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The deletes behind FR-JOB-002 and FR-JOB-013 ({@code data-model-notifications.md} §1-§2). Direct
 * SQL against {@code friend_request} and {@code room_invitation} follows {@code
 * room.RoomInvitationRepository}'s cross-schema precedent: the only thing this package does to
 * either table is delete an expired row, and the foreign key cascade is the mechanism.
 */
@Repository
class NotificationPurgeRepository {

  private final JdbcClient jdbcClient;

  NotificationPurgeRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  /**
   * FR-JOB-002: the predicate is {@code notification_purge_idx}'s own, so an actionable
   * notification (one with a subject) can never match — it is the index, not a code check, that
   * keeps this sweep off them.
   */
  int purgeSubjectlessOlderThan(Duration retention) {
    return jdbcClient
        .sql(
            "delete from notification where friend_request_id is null and invitation_id is null"
                + " and created_at <= now() - make_interval(secs => :seconds)")
        .param("seconds", (double) retention.toSeconds())
        .update();
  }

  /** FR-JOB-013: scans {@code friend_request_expiry_idx}; the notification cascades away. */
  int purgeExpiredFriendRequests() {
    return jdbcClient.sql("delete from friend_request where expires_at <= now()").update();
  }

  /** FR-JOB-013: scans {@code room_invitation_expiry_idx}; the notification cascades away. */
  int purgeExpiredRoomInvitations() {
    return jdbcClient.sql("delete from room_invitation where expires_at <= now()").update();
  }
}
