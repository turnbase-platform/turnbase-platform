package io.turnbase.platform;

import org.junit.jupiter.api.Test;

/**
 * Boots the real application through its own {@code main}, on ephemeral ports, against a real
 * Postgres. Moved from a unit test to an *IT with IMP-M0-06: Flyway now runs at startup
 * (schema-migrations.md §3), so a full boot needs a real database and belongs in the tier that
 * provides one (conventions.md §3 — "integration tests run migrations").
 *
 * <p>{@code main} launches its own {@link org.springframework.boot.SpringApplication}, outside
 * Spring's test context machinery, so the datasource is passed as a command-line argument rather
 * than through {@code @ServiceConnection} — that annotation only wires a {@code TestContext}, which
 * this test does not create.
 */
class TurnBaseApplicationIT extends PostgresIntegrationTest {

  @Test
  void mainStartsTheApplication() {
    TurnBaseApplication.main(
        new String[] {
          "--server.port=0",
          "--management.server.port=0",
          "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
          "--spring.datasource.username=" + POSTGRES.getUsername(),
          "--spring.datasource.password=" + POSTGRES.getPassword()
        });
  }
}
