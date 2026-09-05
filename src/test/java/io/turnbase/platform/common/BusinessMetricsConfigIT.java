package io.turnbase.platform.common;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * FR-MET-003 (observability.md §2.1): the four business gauges are re-derived from real rows, not
 * asserted against a mock. `turnbase_friend_requests_pending` in particular exercises IMP-GATE-20's
 * fix — {@code friend_request} has no {@code status} column, so every row counts.
 */
@Testcontainers
class BusinessMetricsConfigIT {

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine");

  private JdbcTemplate jdbcTemplate;

  @BeforeEach
  void migrateAndConnect() {
    Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .load()
        .migrate();
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setUrl(POSTGRES.getJdbcUrl());
    dataSource.setUsername(POSTGRES.getUsername());
    dataSource.setPassword(POSTGRES.getPassword());
    jdbcTemplate = new JdbcTemplate(dataSource);
  }

  @Test
  void exposesTheFourGaugesFromRealRows() {
    UUID gameId = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO game (game_id, title, short_description, badge) VALUES (?, ?, ?, 'OFFICIAL')",
        gameId,
        "Test Game",
        "A game used only by this test");

    UUID openRoomId = UUID.randomUUID();
    UUID closedRoomId = UUID.randomUUID();
    UUID sessionId = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO room (room_id, game_id, game_version, visibility, state, active_session_id)"
            + " VALUES (?, ?, '1.0.0', 'PUBLIC', 'IN_PROGRESS', ?)",
        openRoomId,
        gameId,
        sessionId);
    jdbcTemplate.update(
        "INSERT INTO room (room_id, game_id, game_version, visibility, state)"
            + " VALUES (?, ?, '1.0.0', 'PUBLIC', 'CLOSED')",
        closedRoomId,
        gameId);

    UUID sender = UUID.randomUUID();
    UUID recipient = UUID.randomUUID();
    insertRegisteredUser(sender, "alice");
    insertRegisteredUser(recipient, "bob");
    jdbcTemplate.update(
        "INSERT INTO friend_request (request_id, sender_id, recipient_id, expires_at)"
            + " VALUES (?, ?, ?, now() + interval '7 days')",
        UUID.randomUUID(),
        sender,
        recipient);

    SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    BusinessMetricsConfig config = new BusinessMetricsConfig(jdbcTemplate, meterRegistry);

    config.refresh();

    assertThat(meterRegistry.get("turnbase_rooms_active").gauge().value()).isEqualTo(1.0);
    assertThat(meterRegistry.get("turnbase_game_sessions_active").gauge().value()).isEqualTo(1.0);
    assertThat(meterRegistry.get("turnbase_users_registered").gauge().value()).isEqualTo(2.0);
    assertThat(meterRegistry.get("turnbase_friend_requests_pending").gauge().value())
        .isEqualTo(1.0);
  }

  private void insertRegisteredUser(UUID userId, String username) {
    jdbcTemplate.update(
        "INSERT INTO app_user (user_id, kind, username, username_skeleton, email, email_source)"
            + " VALUES (?, 'REGISTERED', ?, ?, ?, 'SELF')",
        userId,
        username,
        username,
        username + "@example.test");
  }
}
