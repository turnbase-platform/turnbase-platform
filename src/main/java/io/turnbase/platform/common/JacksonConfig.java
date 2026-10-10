package io.turnbase.platform.common;

import org.springframework.boot.jackson.autoconfigure.JsonFactoryBuilderCustomizer;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.cfg.DateTimeFeature;

/**
 * The one place the platform's Jackson 3 {@code JsonMapper} is customised (ADR-0016 §2). Boot's
 * auto-configured mapper stays the single instance; this only pins what must not move.
 *
 * <p>Jackson 3 changed several defaults relative to Jackson 2. Each one that could reach a REST
 * body, a Redis value or a {@code jsonb} column is set explicitly to the Jackson 2 behaviour, so
 * the wire format is whatever this class says rather than whatever the library defaults to.
 */
@Configuration(proxyBeanMethods = false)
public class JacksonConfig {

  /** {@code input-validation.md} § Limits: nesting, string and number length of a parsed body. */
  static final int MAX_NESTING_DEPTH = 100;

  static final int MAX_STRING_LENGTH = 256 * 1024;
  static final int MAX_NUMBER_LENGTH = 1_000;

  @Bean
  JsonMapperBuilderCustomizer platformJsonMapperPins() {
    return builder ->
        builder
            // Jackson 3 sorts members alphabetically; Jackson 2 kept declaration order.
            .disable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .disable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .disable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  }

  @Bean
  JsonFactoryBuilderCustomizer platformStreamReadConstraints() {
    return builder ->
        builder.streamReadConstraints(
            StreamReadConstraints.builder()
                .maxNestingDepth(MAX_NESTING_DEPTH)
                .maxStringLength(MAX_STRING_LENGTH)
                .maxNumberLength(MAX_NUMBER_LENGTH)
                .build());
  }
}
