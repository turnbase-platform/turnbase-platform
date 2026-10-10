package io.turnbase.platform.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * ADR-0016 §2: host-side JSON is Jackson 3. No platform source imports Jackson 2's {@code databind}
 * or {@code core}; {@code com.fasterxml.jackson.annotation} is shared by both and stays.
 */
class JacksonImportRuleTest {

  private static final List<String> FORBIDDEN =
      List.of("com.fasterxml.jackson.databind", "com.fasterxml.jackson.core");

  @Test
  void noMainOrTestSourceUsesJackson2DatabindOrCore() throws IOException {
    List<String> offenders;
    try (Stream<Path> sources = Files.walk(Path.of("src"))) {
      offenders =
          sources
              .filter(p -> p.toString().endsWith(".java"))
              .filter(p -> !p.endsWith("JacksonImportRuleTest.java"))
              .filter(JacksonImportRuleTest::mentionsForbidden)
              .map(Path::toString)
              .toList();
    }

    assertThat(offenders).isEmpty();
  }

  private static boolean mentionsForbidden(Path file) {
    try {
      String text = Files.readString(file);
      return FORBIDDEN.stream().anyMatch(text::contains);
    } catch (IOException e) {
      throw new IllegalStateException(file.toString(), e);
    }
  }
}
