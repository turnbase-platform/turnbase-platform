package io.turnbase.platform.upload;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;

/** {@code bundle-format.md} §3.2 Phase F, stages F1-F5. */
class BackendJarAnalyzerTest {

  private static final String GDK_SOURCE =
      """
      package io.turnbase.gdk;
      public interface TurnBaseGame<S> {}
      """;
  private static final String STATE_SOURCE =
      """
      package com.example;
      public final class ChessState {
        public ChessState() {}
      }
      """;
  private static final String VALID_GAME_SOURCE =
      """
      package com.example;
      public final class ChessGame implements io.turnbase.gdk.TurnBaseGame<ChessState> {
        public ChessGame() {}
      }
      """;

  private final ArchiveReader archiveReader = new ArchiveReader();
  private final BackendJarAnalyzer analyzer = new BackendJarAnalyzer(archiveReader);
  private static final ArchiveReader.Limits LIMITS =
      new ArchiveReader.Limits(1_000_000_000L, 256_000_000L, 100, 20_000);

  @Test
  void validEntryPointResolvesCleanly() {
    Map<String, byte[]> classes = compileFixture(VALID_GAME_SOURCE);
    byte[] jar = jarOf(classes, "com/example/ChessGame", "com/example/ChessState");

    BackendJarAnalyzer.Result result = analyzer.analyze(jar, "com.example.ChessGame", LIMITS);

    assertThat(result.errors()).isEmpty();
    assertThat(result.entryClassInternalName()).isEqualTo("com/example/ChessGame");
  }

  @Test
  void entryPointNotImplementingTurnBaseGameFails() {
    Map<String, byte[]> classes =
        compileFixture(
            """
            package com.example;
            public final class NotAGame {
              public NotAGame() {}
            }
            """);
    byte[] jar = jarOf(classes, "com/example/NotAGame");

    BackendJarAnalyzer.Result result = analyzer.analyze(jar, "com.example.NotAGame", LIMITS);

    assertThat(result.errors())
        .anySatisfy(e -> assertThat(e.check()).isEqualTo("backend.entryPoint"));
  }

  @Test
  void abstractEntryPointFails() {
    Map<String, byte[]> classes =
        compileFixture(
            """
            package com.example;
            public abstract class AbstractGame implements io.turnbase.gdk.TurnBaseGame<ChessState> {
              public AbstractGame() {}
            }
            """);
    byte[] jar = jarOf(classes, "com/example/AbstractGame", "com/example/ChessState");

    BackendJarAnalyzer.Result result = analyzer.analyze(jar, "com.example.AbstractGame", LIMITS);

    assertThat(result.errors())
        .anySatisfy(e -> assertThat(e.check()).isEqualTo("backend.entryPoint"));
  }

  @Test
  void entryPointWithoutPublicNoArgConstructorFails() {
    Map<String, byte[]> classes =
        compileFixture(
            """
            package com.example;
            public final class NoDefaultCtorGame implements io.turnbase.gdk.TurnBaseGame<ChessState> {
              public NoDefaultCtorGame(int seed) {}
            }
            """);
    byte[] jar = jarOf(classes, "com/example/NoDefaultCtorGame", "com/example/ChessState");

    BackendJarAnalyzer.Result result =
        analyzer.analyze(jar, "com.example.NoDefaultCtorGame", LIMITS);

    assertThat(result.errors())
        .anySatisfy(e -> assertThat(e.check()).isEqualTo("backend.entryPoint"));
  }

  @Test
  void gdkClassesInsideTheJarAreFatal() {
    Map<String, byte[]> classes = compileFixture(VALID_GAME_SOURCE);
    byte[] jar =
        jarOf(
            classes,
            "com/example/ChessGame",
            "com/example/ChessState",
            "io/turnbase/gdk/TurnBaseGame");

    BackendJarAnalyzer.Result result = analyzer.analyze(jar, "com.example.ChessGame", LIMITS);

    assertThat(result.errors())
        .anySatisfy(e -> assertThat(e.check()).isEqualTo("backend.gdkClasses"));
  }

  @Test
  void shadedJacksonIsAWarningNotAFailure() {
    Map<String, byte[]> classes = compileFixture(VALID_GAME_SOURCE);
    Map<String, byte[]> withJackson = new LinkedHashMap<>(classes);
    // Long enough to pass the class-file-header length guard, with a legal major version (52 =
    // Java 8) at bytes 6-7 — F4's shading check only looks at the entry name, but F2 still runs
    // over every ".class" entry first and this must not spuriously fail it.
    withJackson.put(
        "com/fasterxml/jackson/core/Fake",
        new byte[] {(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE, 0, 0, 0, 52});
    byte[] jar =
        jarOf(
            withJackson,
            "com/example/ChessGame",
            "com/example/ChessState",
            "com/fasterxml/jackson/core/Fake");

    BackendJarAnalyzer.Result result = analyzer.analyze(jar, "com.example.ChessGame", LIMITS);

    assertThat(result.errors()).isEmpty();
    assertThat(result.warnings())
        .anySatisfy(w -> assertThat(w.check()).isEqualTo("backend.shadedRuntime"));
  }

  @Test
  void classFileVersionAboveJava21IsFatal() {
    Map<String, byte[]> classes = compileFixture(VALID_GAME_SOURCE);
    Map<String, byte[]> patched = new LinkedHashMap<>(classes);
    byte[] original = patched.get("com/example/ChessGame");
    if (original == null) {
      throw new IllegalStateException("Fixture did not compile com.example.ChessGame");
    }
    byte[] gameBytes = original.clone();
    gameBytes[6] = 0;
    gameBytes[7] = 66; // major version 66 — past Java 21's 65
    patched.put("com/example/ChessGame", gameBytes);
    byte[] jar = jarOf(patched, "com/example/ChessGame", "com/example/ChessState");

    BackendJarAnalyzer.Result result = analyzer.analyze(jar, "com.example.ChessGame", LIMITS);

    assertThat(result.errors())
        .anySatisfy(e -> assertThat(e.check()).isEqualTo("backend.classVersion"));
    assertThat(result.entryClassInternalName()).isNull(); // F1-F2 barrier: F5 never ran
  }

  @Test
  void corruptBackendJarReportsUnderBackendJarCheck() {
    byte[] garbage = "not a jar".getBytes(StandardCharsets.UTF_8);

    BackendJarAnalyzer.Result result = analyzer.analyze(garbage, "com.example.ChessGame", LIMITS);

    assertThat(result.errors()).anySatisfy(e -> assertThat(e.check()).isEqualTo("backend.jar"));
  }

  private static Map<String, byte[]> compileFixture(String extraSource) {
    java.util.regex.Matcher matcher =
        java.util.regex.Pattern.compile("class (\\w+)").matcher(extraSource);
    if (!matcher.find()) {
      throw new IllegalArgumentException("Fixture source has no class declaration: " + extraSource);
    }
    String extraFqcn = "com.example." + matcher.group(1);
    Map<String, String> sources =
        Map.of(
            "io.turnbase.gdk.TurnBaseGame",
            GDK_SOURCE,
            "com.example.ChessState",
            STATE_SOURCE,
            extraFqcn,
            extraSource);
    return TestJavaCompiler.compile(true, sources);
  }

  private static byte[] jarOf(Map<String, byte[]> classesByInternalName, String... include) {
    try {
      ByteArrayOutputStream buffer = new ByteArrayOutputStream();
      try (ZipOutputStream zos = new ZipOutputStream(buffer, StandardCharsets.UTF_8)) {
        for (String internalName : include) {
          byte[] bytes = classesByInternalName.get(internalName);
          if (bytes == null) {
            throw new IllegalArgumentException("No compiled class for " + internalName);
          }
          zos.putNextEntry(new ZipEntry(internalName + ".class"));
          zos.write(bytes);
          zos.closeEntry();
        }
      }
      return buffer.toByteArray();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
