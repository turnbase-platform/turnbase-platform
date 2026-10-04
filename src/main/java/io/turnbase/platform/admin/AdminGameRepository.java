package io.turnbase.platform.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.turnbase.platform.notification.NotificationPersistedEvent;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Direct SQL against {@code game}, {@code game_review}, {@code app_user} and {@code notification} —
 * {@code data-model-catalog.md} §1/§2/§4. Follows {@code room.RoomInvitationRepository}'s
 * established precedent of direct-SQL-against-another-feature's-table (here, {@code account}'s
 * {@code app_user} and {@code notification}'s own table) rather than a cross-module API that does
 * not exist yet — the same precedent {@code upload.GameVersionRepository} already uses for {@code
 * game}/{@code game_version}.
 */
@Repository
class AdminGameRepository {

  private static final String TYPE_GAME_APPROVED = "GAME_APPROVED";
  private static final String TYPE_GAME_REJECTED = "GAME_REJECTED";

  private final JdbcClient jdbcClient;
  private final ObjectMapper objectMapper;
  private final ApplicationEventPublisher eventPublisher;

  AdminGameRepository(
      JdbcClient jdbcClient, ObjectMapper objectMapper, ApplicationEventPublisher eventPublisher) {
    this.jdbcClient = jdbcClient;
    this.objectMapper = objectMapper;
    this.eventPublisher = eventPublisher;
  }

  /** {@code admin-rest-api.md} §3 — the one row-creation mechanism this document supplies. */
  UUID insertGame() {
    UUID gameId = UUID.randomUUID();
    jdbcClient
        .sql(
            """
            insert into game (game_id, owner_user_id, title, short_description, badge, listed)
            values (:gameId, null, '', '', 'OFFICIAL', true)
            """)
        .param("gameId", gameId)
        .update();
    return gameId;
  }

  /** {@code admin-rest-api.md} §5.1, oldest first. */
  List<PendingReview> listPending() {
    return jdbcClient
        .sql(
            """
            select g.game_id, g.title, u.username as developer_username, r.submitted_at
              from game_review r
              join game g on g.game_id = r.game_id
              left join app_user u on u.user_id = g.owner_user_id
             where r.decided_at is null
             order by r.submitted_at asc
            """)
        .query(
            (rs, rowNum) ->
                new PendingReview(
                    rs.getObject("game_id", UUID.class),
                    rs.getString("title"),
                    rs.getString("developer_username"),
                    requireInstant(rs.getTimestamp("submitted_at"))))
        .list();
  }

  /**
   * {@code admin-rest-api.md} §5.2 — not scoped to pending games; {@code empty} is a {@code 404}.
   */
  Optional<GameDetail> findDetail(UUID gameId) {
    return jdbcClient
        .sql(
            """
            select g.game_id, g.title, g.short_description, g.badge, g.listed,
                   u.username as developer_username,
                   gv.version as active_version, gv.activated_at as active_activated_at,
                   r.submitted_at as open_review_submitted_at
              from game g
              left join app_user u on u.user_id = g.owner_user_id
              left join game_version gv on gv.version_id = g.active_version_id
              left join game_review r on r.game_id = g.game_id and r.decided_at is null
             where g.game_id = :gameId
            """)
        .param("gameId", gameId)
        .query(
            (rs, rowNum) -> {
              String activeVersion = rs.getString("active_version");
              Timestamp activatedAt = rs.getTimestamp("active_activated_at");
              Timestamp openReviewSubmittedAt = rs.getTimestamp("open_review_submitted_at");
              return new GameDetail(
                  rs.getObject("game_id", UUID.class),
                  rs.getString("title"),
                  rs.getString("short_description"),
                  rs.getString("badge"),
                  rs.getBoolean("listed"),
                  rs.getString("developer_username"),
                  activeVersion == null
                      ? null
                      : new ActiveVersion(activeVersion, requireInstant(activatedAt)),
                  openReviewSubmittedAt == null ? null : openReviewSubmittedAt.toInstant());
            })
        .optional();
  }

  /**
   * {@code admin-rest-api.md} §5.3 — approve. {@code empty} means no open review for this game
   * (caller's {@code 404}); a row lock on {@code game_review} isn't needed beyond {@code
   * game_review_open_key}'s own uniqueness, since two concurrent decisions on the same open review
   * both racing this update is exactly what "one open review at a time" already prevents.
   */
  @Transactional
  Optional<Decision> approve(UUID gameId) {
    return decide(
        gameId,
        (openReview) -> {
          jdbcClient
              .sql(
                  "update game_review set decided_at = now(), decision = 'APPROVED',"
                      + " decided_by = 'admin' where review_id = :reviewId")
              .param("reviewId", openReview.reviewId())
              .update();
          jdbcClient
              .sql("update game set badge = 'VERIFIED', listed = true where game_id = :gameId")
              .param("gameId", gameId)
              .update();
          if (openReview.developerUserId() != null) {
            insertNotification(
                openReview.developerUserId(),
                TYPE_GAME_APPROVED,
                Map.of("gameId", gameId.toString(), "title", openReview.title()));
          }
        });
  }

  /** {@code admin-rest-api.md} §5.4 — reject. Badge and listing are left untouched (FR-REV-006). */
  @Transactional
  Optional<Decision> reject(UUID gameId, String reason) {
    return decide(
        gameId,
        (openReview) -> {
          jdbcClient
              .sql(
                  "update game_review set decided_at = now(), decision = 'REJECTED',"
                      + " rejection_reason = :reason, decided_by = 'admin'"
                      + " where review_id = :reviewId")
              .param("reason", reason)
              .param("reviewId", openReview.reviewId())
              .update();
          if (openReview.developerUserId() != null) {
            insertNotification(
                openReview.developerUserId(),
                TYPE_GAME_REJECTED,
                Map.of("gameId", gameId.toString(), "title", openReview.title(), "reason", reason));
          }
        });
  }

  private Optional<Decision> decide(UUID gameId, java.util.function.Consumer<OpenReview> action) {
    Optional<OpenReview> openReview =
        jdbcClient
            .sql(
                "select r.review_id, g.owner_user_id, g.title from game_review r"
                    + " join game g on g.game_id = r.game_id"
                    + " where r.game_id = :gameId and r.decided_at is null for update of r")
            .param("gameId", gameId)
            .query(
                (rs, rowNum) ->
                    new OpenReview(
                        rs.getObject("review_id", UUID.class),
                        rs.getObject("owner_user_id", UUID.class),
                        rs.getString("title")))
            .optional();
    if (openReview.isEmpty()) {
      return Optional.empty();
    }
    action.accept(openReview.get());
    return Optional.of(new Decision());
  }

  private void insertNotification(UUID userId, String type, Map<String, Object> payload) {
    UUID notificationId = UUID.randomUUID();
    jdbcClient
        .sql(
            """
            insert into notification (notification_id, user_id, type, payload)
            values (:notificationId, :userId, :type, :payload::jsonb)
            """)
        .param("notificationId", notificationId)
        .param("userId", userId)
        .param("type", type)
        .param("payload", jsonOf(payload))
        .update();
    eventPublisher.publishEvent(new NotificationPersistedEvent(notificationId, userId));
  }

  private String jsonOf(Map<String, Object> payload) {
    try {
      return objectMapper.writeValueAsString(payload);
    } catch (Exception e) {
      throw new IllegalStateException("Failed to serialize notification payload", e);
    }
  }

  private static Instant requireInstant(Timestamp timestamp) {
    return timestamp.toInstant();
  }

  record PendingReview(
      UUID gameId, String title, @Nullable String developerUsername, Instant submittedAt) {}

  record ActiveVersion(String version, Instant activatedAt) {}

  record GameDetail(
      UUID gameId,
      String title,
      String shortDescription,
      String badge,
      boolean listed,
      @Nullable String developerUsername,
      @Nullable ActiveVersion activeVersion,
      @Nullable Instant openReviewSubmittedAt) {}

  private record OpenReview(UUID reviewId, @Nullable UUID developerUserId, String title) {}

  /** A marker that a decision was actually made — {@code empty} from {@link #decide} is the 404. */
  static final class Decision {}
}
