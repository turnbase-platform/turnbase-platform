package io.turnbase.platform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;

/**
 * {@code admin.AdminManagementContextConfiguration} registers the whole {@code admin} package's
 * beans into the management child context via explicit {@code @Import} (itself discovered only
 * through the {@code ManagementContextConfiguration.imports} SPI, not component scanning). The
 * exclude filter below keeps this, the main, context's default scan from also picking up that
 * {@code @Configuration} class and everything it {@code @Import}s — without it, every admin bean is
 * registered twice (once here, once in the child) and the child context fails to start on a {@code
 * NoUniqueBeanDefinitionException}. {@code @ConfigurationPropertiesScan} is a separate mechanism
 * this filter does not touch, so {@code admin.AdminProperties} still binds here, exactly as the
 * management child context's beans need it to (autowired from this, their parent, context).
 */
@SpringBootApplication
@ComponentScan(
    excludeFilters =
        @ComponentScan.Filter(
            type = FilterType.REGEX,
            pattern = "io\\.turnbase\\.platform\\.admin\\..*"))
@ConfigurationPropertiesScan
public class TurnBaseApplication {

  public static void main(String[] args) {
    SpringApplication.run(TurnBaseApplication.class, args);
  }
}
