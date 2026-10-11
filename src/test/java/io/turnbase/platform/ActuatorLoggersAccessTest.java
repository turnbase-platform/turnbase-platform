package io.turnbase.platform;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.endpoint.Access;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * IMP-M10-20: the unauthenticated management listener must not accept {@code POST
 * /actuator/loggers/{name}}; the write operation is refused by configuration, read stays.
 */
class ActuatorLoggersAccessTest {

  @Test
  void loggersEndpointIsReadOnlyAndStillExposed() {
    new ApplicationContextRunner()
        .withInitializer(new ConfigDataApplicationContextInitializer())
        .run(
            context -> {
              Binder binder = Binder.get(context.getEnvironment());
              assertThat(binder.bind("management.endpoint.loggers.access", Access.class).get())
                  .isEqualTo(Access.READ_ONLY);
              assertThat(
                      binder.bind("management.endpoints.web.exposure.include", String.class).get())
                  .contains("loggers");
            });
  }
}
