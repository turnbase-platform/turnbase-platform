package io.turnbase.platform.notification;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Direct SQL against {@code notification} ({@code data-model-notifications.md} §1) — this package
 * owns that table, so unlike {@code room.RoomInvitationRepository}'s cross-schema reads this is
 * entirely within its own boundary.
 */
@Repository
class NotificationRepository {

  private final JdbcClient jdbcClient;
  private final ObjectMapper objectMapper;

  NotificationRepository(JdbcClient jdbcClient, ObjectMapper objectMapper) {
    this.jdbcClient = jdbcClient;
    this.objectMapper = objectMapper;
  }

  /**
   * {@code notification-rest-api.md} §2's fixed order: {@code created_at DESC, notification_id
   * DESC}.
   */
  List<NotificationRow> list(UUID userId, int limit, int offset) {
    return jdbcClient
        .sql(
            """
            select notification_id, type, payload, created_at, read_at from notification
            where user_id = :userId
            order by created_at desc, notification_id desc
            limit :limit offset :offset
            """)
        .param("userId", userId)
        .param("limit", limit)
        .param("offset", offset)
        .query(this::mapRow)
        .list();
  }

  long count(UUID userId) {
    return jdbcClient
        .sql("select count(*) from notification where user_id = :userId")
        .param("userId", userId)
        .query(Long.class)
        .single();
  }

  /** {@code notification-rest-api.md} §3, served by {@code notification_unread_idx}. */
  long unreadCount(UUID userId) {
    return jdbcClient
        .sql("select count(*) from notification where user_id = :userId and read_at is null")
        .param("userId", userId)
        .query(Long.class)
        .single();
  }

  /**
   * {@code notification-rest-api.md} §4: the guarded one-way {@code read_at} transition. Empty
   * means no such notification owned by {@code userId} ({@code 404}); otherwise the row after the
   * (possibly no-op) update.
   */
  @Transactional
  Optional<NotificationRow> markRead(UUID notificationId, UUID userId) {
    Optional<NotificationRow> existing =
        jdbcClient
            .sql(
                "select notification_id, type, payload, created_at, read_at from notification"
                    + " where notification_id = :id and user_id = :userId")
            .param("id", notificationId)
            .param("userId", userId)
            .query(this::mapRow)
            .optional();
    if (existing.isEmpty() || existing.get().readAt() != null) {
      return existing;
    }
    return jdbcClient
        .sql(
            """
            update notification set read_at = now()
            where notification_id = :id and user_id = :userId and read_at is null
            returning notification_id, type, payload, created_at, read_at
            """)
        .param("id", notificationId)
        .param("userId", userId)
        .query(this::mapRow)
        .optional();
  }

  /**
   * {@code notification-rest-api.md} §5. FR-NOTIF-004's mark-all, same guarded update, no
   * predicate.
   */
  void markAllRead(UUID userId) {
    jdbcClient
        .sql("update notification set read_at = now() where user_id = :userId and read_at is null")
        .param("userId", userId)
        .update();
  }

  /**
   * {@code notification-rest-api.md} §6: irreversible delete, {@code 204} whether or not a row
   * matched.
   */
  void delete(UUID notificationId, UUID userId) {
    jdbcClient
        .sql("delete from notification where notification_id = :id and user_id = :userId")
        .param("id", notificationId)
        .param("userId", userId)
        .update();
  }

  /**
   * {@code sse-notifications.md} § Offline delivery: every row with {@code delivered_at IS NULL}
   * for this user, stamped in the same statement and returned oldest ({@code event_seq}) first for
   * the caller to write to the just-opened stream.
   */
  @Transactional
  List<UndeliveredRow> markUndeliveredAsDelivered(UUID userId) {
    List<UndeliveredRow> rows =
        jdbcClient
            .sql(
                """
                update notification set delivered_at = now()
                where user_id = :userId and delivered_at is null
                returning notification_id, event_seq, type, payload, created_at
                """)
            .param("userId", userId)
            .query(
                (rs, rowNum) ->
                    new UndeliveredRow(
                        rs.getObject("notification_id", UUID.class),
                        rs.getLong("event_seq"),
                        rs.getString("type"),
                        parsePayload(rs.getString("payload")),
                        rs.getTimestamp("created_at").toInstant()))
            .list();
    return rows.stream().sorted(Comparator.comparingLong(UndeliveredRow::eventSeq)).toList();
  }

  /**
   * The live push's claim: stamps one row delivered if nobody has yet. Empty when the stream-open
   * catch-up got there first, so the frame is sent exactly once.
   */
  Optional<UndeliveredRow> markDelivered(UUID notificationId) {
    return jdbcClient
        .sql(
            """
            update notification set delivered_at = now()
            where notification_id = :id and delivered_at is null
            returning notification_id, event_seq, type, payload, created_at
            """)
        .param("id", notificationId)
        .query(
            (rs, rowNum) ->
                new UndeliveredRow(
                    rs.getObject("notification_id", UUID.class),
                    rs.getLong("event_seq"),
                    rs.getString("type"),
                    parsePayload(rs.getString("payload")),
                    rs.getTimestamp("created_at").toInstant()))
        .optional();
  }

  private NotificationRow mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
    Timestamp readAt = rs.getTimestamp("read_at");
    return new NotificationRow(
        rs.getObject("notification_id", UUID.class),
        rs.getString("type"),
        parsePayload(rs.getString("payload")),
        rs.getTimestamp("created_at").toInstant(),
        readAt == null ? null : readAt.toInstant());
  }

  private Map<String, Object> parsePayload(String json) {
    try {
      return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
    } catch (Exception e) {
      throw new IllegalStateException("Failed to parse notification payload", e);
    }
  }

  record NotificationRow(
      UUID notificationId,
      String type,
      Map<String, Object> payload,
      Instant createdAt,
      @Nullable Instant readAt) {}

  record UndeliveredRow(
      UUID notificationId,
      long eventSeq,
      String type,
      Map<String, Object> payload,
      Instant createdAt) {}
}
