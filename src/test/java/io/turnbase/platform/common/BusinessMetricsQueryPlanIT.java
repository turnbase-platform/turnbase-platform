package io.turnbase.platform.common;

import static org.assertj.core.api.Assertions.assertThat;

import io.turnbase.platform.PostgresIntegrationTest;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * {@code IMP-M16-09}: the two {@code count(*)} gauges that {@link BusinessMetricsConfig} runs every
 * 30 seconds read partial indexes of the baseline, not the table, while closed rooms (kept 7 days,
 * FR-RET-006) vastly outnumber open ones. The plans are asserted, and printed for the record.
 */
class BusinessMetricsQueryPlanIT extends PostgresIntegrationTest {

  private static final int CLOSED_ROOMS = 100_000;
  private static final int OPEN_ROOMS = 300;

  @Test
  void bothRoomGaugeQueriesUseAPartialIndexWith100000ClosedRooms() {
    Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .configuration(java.util.Map.of("flyway.postgresql.transactional.lock", "false"))
        .load()
        .migrate();
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setUrl(POSTGRES.getJdbcUrl());
    dataSource.setUsername(POSTGRES.getUsername());
    dataSource.setPassword(POSTGRES.getPassword());
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);

    UUID gameId = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO game (game_id, title, short_description, badge) VALUES (?, ?, ?, 'OFFICIAL')",
        gameId,
        "Test Game",
        "A game used only by this test");
    jdbc.update(
        "INSERT INTO room (room_id, game_id, game_version, visibility, state, closed_at)"
            + " SELECT gen_random_uuid(), ?, '1.0.0', 'PUBLIC', 'CLOSED', now()"
            + " FROM generate_series(1, ?)",
        gameId,
        CLOSED_ROOMS);
    jdbc.update(
        "INSERT INTO room (room_id, game_id, game_version, visibility, state, active_session_id)"
            + " SELECT gen_random_uuid(), ?, '1.0.0', 'PUBLIC',"
            + " CASE WHEN g % 2 = 0 THEN 'IN_PROGRESS' ELSE 'LOBBY' END,"
            + " CASE WHEN g % 2 = 0 THEN gen_random_uuid() END"
            + " FROM generate_series(1, ?) g",
        gameId, OPEN_ROOMS);
    jdbc.execute("VACUUM ANALYZE room");

    assertThat(jdbc.queryForObject(BusinessMetricsConfig.ROOMS_ACTIVE_SQL, Long.class))
        .isEqualTo(OPEN_ROOMS);
    assertThat(jdbc.queryForObject(BusinessMetricsConfig.GAME_SESSIONS_ACTIVE_SQL, Long.class))
        .isEqualTo(OPEN_ROOMS / 2);

    String roomsPlan = plan(jdbc, BusinessMetricsConfig.ROOMS_ACTIVE_SQL);
    String sessionsPlan = plan(jdbc, BusinessMetricsConfig.GAME_SESSIONS_ACTIVE_SQL);
    System.out.println("EXPLAIN rooms_active:\n" + roomsPlan);
    System.out.println("EXPLAIN game_sessions_active:\n" + sessionsPlan);

    // The planner may pick any partial index that excludes CLOSED: room_open_game_idx is narrower
    // per entry than room_open_state_idx and is chosen for the first gauge. Either is an index
    // scan.
    assertThat(roomsPlan).contains("Index Only Scan").doesNotContain("Seq Scan");
    assertThat(sessionsPlan)
        .contains("Index Only Scan using room_active_session_idx")
        .doesNotContain("Seq Scan");
  }

  private static String plan(JdbcTemplate jdbc, String sql) {
    List<String> lines = jdbc.queryForList("EXPLAIN " + sql, String.class);
    return String.join("\n", lines);
  }
}
