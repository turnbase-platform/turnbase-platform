package io.turnbase.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * {@code IMP-M17-03}, FR-DBQ-005: the connection-pool maximum is set in {@code application.yml},
 * not left to Hikari's library default, and {@code SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE}
 * overrides it. Hikari opens no connection until first use, so no database is needed.
 */
class ConnectionPoolSizeTest {

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withInitializer(new ConfigDataApplicationContextInitializer())
          .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class))
          .withPropertyValues("spring.datasource.url=jdbc:postgresql://localhost:5432/none");

  @Test
  void defaultsToTenAndIsFixedSize() {
    contextRunner.run(
        context -> {
          HikariDataSource pool = (HikariDataSource) context.getBean(DataSource.class);
          assertThat(pool.getMaximumPoolSize()).isEqualTo(10);
          // Unset (-1): Hikari then makes the pool fixed-size at its maximum.
          assertThat(pool.getMinimumIdle()).isEqualTo(-1);
        });
  }

  @Test
  void followsTheProperty() {
    contextRunner
        .withPropertyValues("spring.datasource.hikari.maximum-pool-size=7")
        .run(
            context ->
                assertThat(
                        ((HikariDataSource) context.getBean(DataSource.class)).getMaximumPoolSize())
                    .isEqualTo(7));
  }
}
