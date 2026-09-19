package io.turnbase.platform.upload;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.tree.ClassNode;

/**
 * {@code bundle-format.md} §4.4's static serializability analysis — F6 (resolving {@code S}) and F7
 * (walking its graph). Fixtures are real, compiled classes ({@link TestJavaCompiler}) so the
 * generic {@code Signature} attribute and the {@code MethodParameters} attribute — the exact
 * GRMP-F4 defect, a lost {@code -parameters} flag — are the real javac output, not hand-assembled
 * approximations.
 */
class StateSerializabilityAnalyzerTest {

  private static final String GDK_SOURCE =
      """
      package io.turnbase.gdk;
      public interface TurnBaseGame<S> {}
      """;

  private final StateSerializabilityAnalyzer analyzer = new StateSerializabilityAnalyzer();

  @Test
  void recordStatePassesWithoutTheConstructorRule() {
    Fixture fixture =
        compile(
            true,
            "package com.example; public final class Game implements io.turnbase.gdk.TurnBaseGame<State> { public Game() {} }",
            "package com.example; public record State(String name, int score) {}");

    var result = analyzer.analyze("com/example/State", fixture.archive());

    assertThat(result.errors()).isEmpty();
  }

  @Test
  void resolveStateTypeFindsTheGenericArgument() {
    Fixture fixture =
        compile(
            true,
            "package com.example; public final class Game implements io.turnbase.gdk.TurnBaseGame<State> { public Game() {} }",
            "package com.example; public record State(String name) {}");

    String resolved =
        analyzer.resolveStateType(entryClassNode(fixture, "com/example/Game"), fixture.archive());

    assertThat(resolved).isEqualTo("com/example/State");
  }

  @Test
  void rawTurnBaseGameUsageIsUnresolvable() {
    Fixture fixture =
        compile(
            true,
            "package com.example; @SuppressWarnings({\"rawtypes\",\"unchecked\"}) public final class Game implements"
                + " io.turnbase.gdk.TurnBaseGame { public Game() {} }",
            "package com.example; public record State(String name) {}");

    String resolved =
        analyzer.resolveStateType(entryClassNode(fixture, "com/example/Game"), fixture.archive());

    assertThat(resolved).isNull();
  }

  @Test
  void missingParametersFlagOnANonRecordCreatorIsFatal_theGrmpF4Defect() {
    // Compiled WITHOUT -parameters and with no no-arg constructor, matching GRMP-F4 exactly.
    Fixture fixture =
        compile(
            false,
            "package com.example; public final class Game implements io.turnbase.gdk.TurnBaseGame<State> { public Game() {} }",
            "package com.example; public final class State {"
                + " private final String name;"
                + " public State(String name) { this.name = name; }"
                + " public String getName() { return name; } }");

    var result = analyzer.analyze("com/example/State", fixture.archive());

    assertThat(result.errors())
        .anySatisfy(e -> assertThat(e.check()).isEqualTo("state.serializable"));
  }

  @Test
  void methodParametersAttributePresentSatisfiesTheRule() {
    // Same shape as above but compiled WITH -parameters — the fix for the GRMP-F4 defect.
    Fixture fixture =
        compile(
            true,
            "package com.example; public final class Game implements io.turnbase.gdk.TurnBaseGame<State> { public Game() {} }",
            "package com.example; public final class State {"
                + " private final String name;"
                + " public State(String name) { this.name = name; }"
                + " public String getName() { return name; } }");

    var result = analyzer.analyze("com/example/State", fixture.archive());

    assertThat(result.errors()).isEmpty();
  }

  @Test
  void jsonPropertyOnConstructorParametersSatisfiesTheRuleEvenWithoutParametersFlag() {
    Fixture fixture =
        compile(
            false,
            "package com.example; public final class Game implements io.turnbase.gdk.TurnBaseGame<State> { public Game() {} }",
            "package com.example; public final class State {"
                + " private final String name;"
                + " public State(@com.fasterxml.jackson.annotation.JsonProperty(\"name\") String name) { this.name = name; }"
                + " public String getName() { return name; } }");

    var result = analyzer.analyze("com/example/State", fixture.archive());

    assertThat(result.errors()).isEmpty();
  }

  @Test
  void mapWithNonSafeKeyTypeIsFatal() {
    Fixture fixture =
        compile(
            true,
            "package com.example; public final class Game implements io.turnbase.gdk.TurnBaseGame<State> { public Game() {} }",
            "package com.example; public record State(java.util.Map<com.example.Position, com.example.Piece> board) {}",
            "package com.example; public record Position(int x, int y) {}",
            "package com.example; public record Piece(String kind) {}");

    var result = analyzer.analyze("com/example/State", fixture.archive());

    assertThat(result.errors())
        .anySatisfy(e -> assertThat(e.check()).isEqualTo("state.serializable"));
  }

  @Test
  void mapWithStringKeyIsFine() {
    Fixture fixture =
        compile(
            true,
            "package com.example; public final class Game implements io.turnbase.gdk.TurnBaseGame<State> { public Game() {} }",
            "package com.example; public record State(java.util.Map<String, com.example.Piece> board) {}",
            "package com.example; public record Piece(String kind) {}");

    var result = analyzer.analyze("com/example/State", fixture.archive());

    assertThat(result.errors()).isEmpty();
  }

  @Test
  void mapWithEnumKeyIsFine() {
    Fixture fixture =
        compile(
            true,
            "package com.example; public final class Game implements io.turnbase.gdk.TurnBaseGame<State> { public Game() {} }",
            "package com.example; public record State(java.util.Map<com.example.Color, Integer> scores) {}",
            "package com.example; public enum Color { RED, BLUE }");

    var result = analyzer.analyze("com/example/State", fixture.archive());

    assertThat(result.errors()).isEmpty();
  }

  @Test
  void interfaceTypedFieldIsFatal() {
    Fixture fixture =
        compile(
            true,
            "package com.example; public final class Game implements io.turnbase.gdk.TurnBaseGame<State> { public Game() {} }",
            "package com.example; public record State(com.example.Shape shape) {}",
            "package com.example; public interface Shape {}");

    var result = analyzer.analyze("com/example/State", fixture.archive());

    assertThat(result.errors())
        .anySatisfy(e -> assertThat(e.check()).isEqualTo("state.serializable"));
  }

  @Test
  void listOfConcreteTypeRecursesAndPasses() {
    Fixture fixture =
        compile(
            true,
            "package com.example; public final class Game implements io.turnbase.gdk.TurnBaseGame<State> { public Game() {} }",
            "package com.example; public record State(java.util.List<com.example.Piece> pieces) {}",
            "package com.example; public record Piece(String kind) {}");

    var result = analyzer.analyze("com/example/State", fixture.archive());

    assertThat(result.errors()).isEmpty();
  }

  @Test
  void objectTypedFieldIsFatal() {
    Fixture fixture =
        compile(
            true,
            "package com.example; public final class Game implements io.turnbase.gdk.TurnBaseGame<State> { public Game() {} }",
            "package com.example; public record State(Object payload) {}");

    var result = analyzer.analyze("com/example/State", fixture.archive());

    assertThat(result.errors())
        .anySatisfy(e -> assertThat(e.check()).isEqualTo("state.serializable"));
  }

  @Test
  void javaTimeFieldIsFatal_noRegisteredModule() {
    Fixture fixture =
        compile(
            true,
            "package com.example; public final class Game implements io.turnbase.gdk.TurnBaseGame<State> { public Game() {} }",
            "package com.example; public record State(java.time.Instant startedAt) {}");

    var result = analyzer.analyze("com/example/State", fixture.archive());

    assertThat(result.errors())
        .anySatisfy(e -> assertThat(e.check()).isEqualTo("state.serializable"));
  }

  @Test
  void optionalFieldIsFatal_noRegisteredModule() {
    Fixture fixture =
        compile(
            true,
            "package com.example; public final class Game implements io.turnbase.gdk.TurnBaseGame<State> { public Game() {} }",
            "package com.example; public record State(java.util.Optional<String> nickname) {}");

    var result = analyzer.analyze("com/example/State", fixture.archive());

    assertThat(result.errors())
        .anySatisfy(e -> assertThat(e.check()).isEqualTo("state.serializable"));
  }

  @Test
  void referenceCycleIsAWarningNotAFailure() {
    Fixture fixture =
        compile(
            true,
            "package com.example; public final class Game implements io.turnbase.gdk.TurnBaseGame<State> { public Game() {} }",
            "package com.example; public record State(com.example.Node root) {}",
            "package com.example; public record Node(String label, Node next) {}");

    var result = analyzer.analyze("com/example/State", fixture.archive());

    assertThat(result.errors()).isEmpty();
    assertThat(result.warnings())
        .anySatisfy(w -> assertThat(w.check()).isEqualTo("state.serializable"));
  }

  private static ClassNode entryClassNode(Fixture fixture, String internalName) {
    var entry = fixture.archive().find(internalName + ".class").orElseThrow();
    return ClassFileUtil.readClassNode(entry.data());
  }

  private record Fixture(ArchiveReadResult archive) {}

  private static Fixture compile(boolean withParameters, String... sources) {
    Map<String, String> byFqcn = new LinkedHashMap<>();
    byFqcn.put("io.turnbase.gdk.TurnBaseGame", GDK_SOURCE);
    for (String source : sources) {
      java.util.regex.Matcher matcher =
          java.util.regex.Pattern.compile("(class|record|interface|enum) (\\w+)").matcher(source);
      if (!matcher.find()) {
        throw new IllegalArgumentException("No type declaration found in: " + source);
      }
      java.util.regex.Matcher pkgMatcher =
          java.util.regex.Pattern.compile("package ([\\w.]+);").matcher(source);
      String pkg = pkgMatcher.find() ? pkgMatcher.group(1) + "." : "";
      byFqcn.put(pkg + matcher.group(2), source);
    }
    Map<String, byte[]> compiled = TestJavaCompiler.compile(withParameters, byFqcn);
    List<ArchiveEntry> entries =
        compiled.entrySet().stream()
            .map(
                e ->
                    new ArchiveEntry(
                        e.getKey() + ".class", false, e.getValue().length, e.getValue()))
            .toList();
    return new Fixture(new ArchiveReadResult(List.of(), entries));
  }
}
