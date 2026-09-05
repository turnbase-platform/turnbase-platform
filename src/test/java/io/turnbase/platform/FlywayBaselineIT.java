package io.turnbase.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * IMP-M0-06's own Done-when clause, checked directly against the Flyway API rather than through a
 * full application boot: the baseline creates every table named in the six schema documents,
 * migrates a clean Postgres 16 to completion, a re-run is a no-op, and the FK graph — a migration
 * that referenced a not-yet-created table would fail outright — admits the creation order used.
 *
 * <p>A dedicated container, not the shared one in {@link PostgresIntegrationTest}: this test's
 * assertions depend on the database being genuinely clean before the first {@code migrate()} call,
 * which a container already migrated by another *IT would not be.
 */
@Testcontainers
class FlywayBaselineIT {

  private static final List<String> EXPECTED_TABLES =
      List.of(
          "app_user",
          "user_auth_method",
          "user_session",
          "user_privacy_settings",
          "friendship",
          "friend_request",
          "game",
          "game_version",
          "game_tag",
          "game_review",
          "room",
          "room_team",
          "room_participant",
          "room_invitation",
          "session_teardown",
          "game_input_outbox",
          "game_session_completion",
          "game_history_entry",
          "notification",
          "shedlock");

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine");

  private Flyway newFlyway() {
    return Flyway.configure()
        .cleanDisabled(false)
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .load();
  }

  /**
   * Each test needs a genuinely clean database, not merely one that has never seen this test method
   * before — the container is shared across both {@code @Test} methods in this class.
   */
  @BeforeEach
  void cleanDatabase() {
    newFlyway().clean();
  }

  @Test
  void migratesCleanlyAndCreatesEveryTable() throws Exception {
    Flyway flyway = newFlyway();

    MigrateResult result = flyway.migrate();

    assertThat(result.success).isTrue();
    assertThat(result.migrationsExecuted).isEqualTo(1);

    try (Connection connection =
        DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
      List<String> actualTables = new ArrayList<>();
      try (ResultSet rs =
          connection.getMetaData().getTables(null, "public", "%", new String[] {"TABLE"})) {
        while (rs.next()) {
          actualTables.add(rs.getString("TABLE_NAME"));
        }
      }
      // flyway_schema_history is Flyway's own bookkeeping table, not one of the six documents'.
      actualTables.remove("flyway_schema_history");

      assertThat(actualTables).containsExactlyInAnyOrderElementsOf(EXPECTED_TABLES);
    }
  }

  @Test
  void rerunIsANoOp() {
    Flyway flyway = newFlyway();

    flyway.migrate();
    MigrateResult second = flyway.migrate();

    assertThat(second.success).isTrue();
    assertThat(second.migrationsExecuted).isEqualTo(0);
  }
}
