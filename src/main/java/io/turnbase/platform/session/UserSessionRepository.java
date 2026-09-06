package io.turnbase.platform.session;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Plain JDBC against {@code user_session} (data-model-accounts.md §3) — no ORM in this repo. */
@Repository
class UserSessionRepository {

  private final JdbcClient jdbcClient;

  UserSessionRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  void insert(UserSession session) {
    jdbcClient
        .sql(
            """
            insert into user_session
              (session_id, user_id, issued_at, expires_at, key_id, user_agent_hash)
            values (:sessionId, :userId, :issuedAt, :expiresAt, :keyId, :userAgentHash)
            """)
        .param("sessionId", session.sessionId())
        .param("userId", session.userId())
        .param("issuedAt", Timestamp.from(session.issuedAt()))
        .param("expiresAt", Timestamp.from(session.expiresAt()))
        .param("keyId", session.keyId())
        .param("userAgentHash", session.userAgentHash())
        .update();
  }

  Optional<UserSession> findById(UUID sessionId) {
    return jdbcClient
        .sql(
            """
            select session_id, user_id, issued_at, expires_at, revoked_at, revoked_reason,
                   key_id, user_agent_hash
            from user_session
            where session_id = :sessionId
            """)
        .param("sessionId", sessionId)
        .query(UserSessionRepository::mapRow)
        .optional();
  }

  /**
   * secrets-and-configuration.md §3.3 step 3: every live session bound to a retired key is revoked
   * with {@code revoked_reason = 'KEY_ROTATED'}. Returns the number of rows revoked, which is
   * normally zero — rotation is only a mass event in the emergency path.
   */
  int revokeAllForKey(String keyId, String reason, Instant revokedAt) {
    return jdbcClient
        .sql(
            """
            update user_session
            set revoked_at = :revokedAt, revoked_reason = :reason
            where key_id = :keyId and revoked_at is null
            """)
        .param("revokedAt", Timestamp.from(revokedAt))
        .param("reason", reason)
        .param("keyId", keyId)
        .update();
  }

  private static UserSession mapRow(ResultSet rs, int rowNum) throws SQLException {
    return new UserSession(
        rs.getObject("session_id", UUID.class),
        rs.getObject("user_id", UUID.class),
        rs.getObject("issued_at", Timestamp.class).toInstant(),
        rs.getObject("expires_at", Timestamp.class).toInstant(),
        toInstant(rs.getObject("revoked_at", Timestamp.class)),
        rs.getString("revoked_reason"),
        rs.getString("key_id"),
        rs.getString("user_agent_hash"));
  }

  private static @Nullable Instant toInstant(@Nullable Timestamp timestamp) {
    return timestamp == null ? null : timestamp.toInstant();
  }
}
