package io.turnbase.platform;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Shared Postgres 16 container for *IT classes that need a real Spring context wired to a real
 * database. The container is a single static field on this base class, so every subclass shares one
 * instance per JVM (conventions.md §3's container-reuse trap) rather than starting its own.
 */
@Testcontainers
public abstract class PostgresIntegrationTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
}
