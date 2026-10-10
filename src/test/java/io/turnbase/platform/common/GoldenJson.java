package io.turnbase.platform.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.messaging.converter.JacksonJsonMessageConverter;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * The one place the wire-format golden tests obtain "the platform's JSON": Boot's own
 * auto-configured {@link JsonMapper} plus {@link JacksonConfig}, exactly as the application wires
 * it. The expected payloads were captured on Jackson 2 first ({@code IMP-M11-03}); only this class
 * changed with the mapper.
 */
public final class GoldenJson {

  private GoldenJson() {}

  public static JsonMapper mapper() {
    AtomicReference<JsonMapper> mapper = new AtomicReference<>();
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
        .withUserConfiguration(JacksonConfig.class)
        .run(context -> mapper.set(context.getBean(JsonMapper.class)));
    return java.util.Objects.requireNonNull(mapper.get());
  }

  /** The body of a STOMP frame as the broker's message converter writes it. */
  public static String stompBody(Object payload) {
    var converter = new JacksonJsonMessageConverter(mapper());
    var message = converter.toMessage(payload, null);
    if (message == null) {
      throw new AssertionError("the converter produced no message");
    }
    return new String((byte[]) message.getPayload(), StandardCharsets.UTF_8);
  }

  /** Same JSON value, ignoring object-member order (for {@code Map.of} bodies, which have none). */
  public static void assertSameJson(String actual, String expected) {
    try {
      var m = mapper();
      assertThat(m.readTree(actual)).isEqualTo(m.readTree(expected));
    } catch (JacksonException e) {
      throw new AssertionError(e);
    }
  }
}
