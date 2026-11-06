package io.turnbase.platform.room;

import io.turnbase.platform.common.BusinessRuleException;
import io.turnbase.platform.common.ForbiddenException;
import io.turnbase.platform.common.NotFoundException;
import io.turnbase.platform.notification.NotificationPersistedEvent;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code room-invitation-rest-api.md} §§2-4 and {@code data-model-notifications.md} §2 ({@code
 * room_invitation}). Direct SQL against {@code app_user}, {@code friendship} and {@code
 * notification} — tables owned by {@code account}, {@code social} and {@code notification}
 * respectively — follows {@code social.SocialRepository}'s precedent, already established in this
 * exact package by {@code RoomReadRepository#isVisible}'s direct {@code friendship} read.
 */
@Repository
class RoomInvitationRepository {

  private static final String TYPE_ROOM_INVITATION = "ROOM_INVITATION";

  private final JdbcClient jdbcClient;
  private final JsonMapper objectMapper;
  private final ApplicationEventPublisher eventPublisher;

  RoomInvitationRepository(
      JdbcClient jdbcClient, JsonMapper objectMapper, ApplicationEventPublisher eventPublisher) {
    this.jdbcClient = jdbcClient;
    this.objectMapper = objectMapper;
    this.eventPublisher = eventPublisher;
  }

  /** {@code room-invitation-rest-api.md} §2's evaluation order, steps 1-7, in one transaction. */
  @Transactional
  SendResult send(
      UUID hostUserId, UUID roomId, String inviteeUsernameSkeleton, Duration retentionWindow) {
    RoomForInvite room =
        jdbcClient
            .sql("select host_user_id, state, game_id from room where room_id = :roomId for update")
            .param("roomId", roomId)
            .query(
                (rs, rowNum) ->
                    new RoomForInvite(
                        rs.getObject("host_user_id", UUID.class),
                        rs.getString("state"),
                        rs.getObject("game_id", UUID.class)))
            .optional()
            .orElseThrow(() -> new NotFoundException("No such room."));
    if (!hostUserId.equals(room.hostUserId())) {
      throw new ForbiddenException("Only the host may invite.");
    }
    if ("CLOSED".equals(room.state())) {
      throw new NotFoundException("No such room.");
    }

    InviteeUser invitee =
        jdbcClient
            .sql(
                """
                select user_id, username from app_user
                where username_skeleton = :skeleton and kind = 'REGISTERED' and deleted_at is null
                """)
            .param("skeleton", inviteeUsernameSkeleton)
            .query(
                (rs, rowNum) ->
                    new InviteeUser(rs.getObject("user_id", UUID.class), rs.getString("username")))
            .optional()
            .orElseThrow(() -> new NotFoundException("No such user."));
    if (invitee.userId().equals(hostUserId)) {
      throw new BusinessRuleException("You cannot invite yourself.");
    }

    Boolean friends =
        jdbcClient
            .sql(
                "select exists(select 1 from friendship where user_id_low = least(:a, :b) and"
                    + " user_id_high = greatest(:a, :b))")
            .param("a", hostUserId)
            .param("b", invitee.userId())
            .query(Boolean.class)
            .single();
    if (!Boolean.TRUE.equals(friends)) {
      throw new BusinessRuleException("You may only invite friends.");
    }

    Optional<ExistingInvitation> existing =
        jdbcClient
            .sql(
                "select invitation_id, created_at, expires_at from room_invitation"
                    + " where room_id = :roomId and invitee_user_id = :inviteeId")
            .param("roomId", roomId)
            .param("inviteeId", invitee.userId())
            .query(
                (rs, rowNum) ->
                    new ExistingInvitation(
                        rs.getObject("invitation_id", UUID.class),
                        requireInstant(rs.getTimestamp("created_at")),
                        requireInstant(rs.getTimestamp("expires_at"))))
            .optional();
    if (existing.isPresent()) {
      return new SendResult(
          existing.get().invitationId(),
          invitee.username(),
          existing.get().createdAt(),
          existing.get().expiresAt());
    }

    UUID invitationId = UUID.randomUUID();
    Instant createdAt = Instant.now();
    Instant expiresAt = createdAt.plus(retentionWindow);
    jdbcClient
        .sql(
            """
            insert into room_invitation (invitation_id, room_id, inviter_user_id, invitee_user_id,
                                          created_at, expires_at)
            values (:invitationId, :roomId, :inviterId, :inviteeId, :createdAt, :expiresAt)
            """)
        .param("invitationId", invitationId)
        .param("roomId", roomId)
        .param("inviterId", hostUserId)
        .param("inviteeId", invitee.userId())
        .param("createdAt", Timestamp.from(createdAt))
        .param("expiresAt", Timestamp.from(expiresAt))
        .update();

    String hostUsername = usernameOf(hostUserId);
    String gameName = gameTitleOf(room.gameId());
    UUID notificationId = UUID.randomUUID();
    jdbcClient
        .sql(
            """
            insert into notification (notification_id, user_id, type, payload, invitation_id)
            values (:notificationId, :userId, :type, :payload::jsonb, :invitationId)
            """)
        .param("notificationId", notificationId)
        .param("userId", invitee.userId())
        .param("type", TYPE_ROOM_INVITATION)
        .param(
            "payload",
            jsonOf(
                Map.of(
                    "roomId",
                    roomId.toString(),
                    "hostUsername",
                    hostUsername,
                    "gameName",
                    gameName)))
        .param("invitationId", invitationId)
        .update();
    eventPublisher.publishEvent(new NotificationPersistedEvent(notificationId, invitee.userId()));

    return new SendResult(invitationId, invitee.username(), createdAt, expiresAt);
  }

  /**
   * {@code room-invitation-rest-api.md} §3. Idempotent — {@code 0} rows affected (no matching
   * pending invitation) is a valid, silent outcome, not an error.
   */
  @Transactional
  Optional<ResolvedInvitation> decline(UUID roomId, UUID inviteeUserId) {
    return deleteReturningInviter(roomId, inviteeUserId);
  }

  /**
   * {@code room-invitation-rest-api.md} §4 — called from {@link RoomRepository#joinRoom} inside its
   * own transaction, only on a successful join. A no-op (empty result) when the caller had no
   * pending invitation to this room.
   */
  Optional<ResolvedInvitation> resolveOnAcceptedJoin(UUID roomId, UUID inviteeUserId) {
    return deleteReturningInviter(roomId, inviteeUserId);
  }

  private Optional<ResolvedInvitation> deleteReturningInviter(UUID roomId, UUID inviteeUserId) {
    return jdbcClient
        .sql(
            """
            with deleted as (
              delete from room_invitation
               where room_id = :roomId and invitee_user_id = :inviteeId
              returning inviter_user_id
            )
            select d.inviter_user_id, au.username as invitee_username
            from deleted d
            join app_user au on au.user_id = :inviteeId
            """)
        .param("roomId", roomId)
        .param("inviteeId", inviteeUserId)
        .query(
            (rs, rowNum) ->
                new ResolvedInvitation(
                    rs.getObject("inviter_user_id", UUID.class), rs.getString("invitee_username")))
        .optional();
  }

  private String usernameOf(UUID userId) {
    return jdbcClient
        .sql("select username from app_user where user_id = :userId")
        .param("userId", userId)
        .query(String.class)
        .single();
  }

  private String gameTitleOf(UUID gameId) {
    return jdbcClient
        .sql("select title from game where game_id = :gameId")
        .param("gameId", gameId)
        .query(String.class)
        .single();
  }

  private static Instant requireInstant(Timestamp timestamp) {
    return timestamp.toInstant();
  }

  /**
   * {@code postgresql} is a runtime-scoped dependency ({@code conventions.md} §1), so this writes
   * the JSON as text and casts it in SQL ({@code ::jsonb} above) rather than binding a driver-typed
   * {@code PGobject}, which would need it at compile time.
   */
  private String jsonOf(Map<String, Object> value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalStateException("Failed to serialize notification payload", e);
    }
  }

  private record RoomForInvite(UUID hostUserId, String state, UUID gameId) {}

  private record InviteeUser(UUID userId, String username) {}

  private record ExistingInvitation(UUID invitationId, Instant createdAt, Instant expiresAt) {}

  record SendResult(
      UUID invitationId, String inviteeUsername, Instant createdAt, Instant expiresAt) {}

  record ResolvedInvitation(UUID inviterUserId, String inviteeUsername) {}
}
