package io.turnbase.platform.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;

/**
 * The one place the wire-format golden tests obtain "the platform's JSON". {@code IMP-M11-03}
 * captured the expected payloads against Jackson 2 first; only this class changes with the mapper.
 */
public final class GoldenJson {

  private GoldenJson() {}

  /** The mapper Spring Boot 3.5 auto-configured: the Spring builder, dates as ISO strings. */
  public static ObjectMapper mapper() {
    return Jackson2ObjectMapperBuilder.json()
        .featuresToDisable(
            com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .build();
  }

  /** The body of a STOMP frame as the broker's message converter writes it. */
  public static String stompBody(Object payload) {
    // AbstractMessageBrokerConfiguration builds the converter's mapper with the Spring builder.
    var converter = new MappingJackson2MessageConverter();
    converter.setObjectMapper(Jackson2ObjectMapperBuilder.json().build());
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
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new AssertionError(e);
    }
  }
}
