package io.turnbase.platform.upload;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

/**
 * Compiles small, real Java sources into {@code .class} bytes for Phase F/state-serializability
 * fixtures — far less error-prone than hand-assembling bytecode with ASM's writer API, and it lets
 * a test control {@code -parameters} (the exact GRMP-F4 defect: a lost {@code -parameters} flag)
 * per compilation.
 */
final class TestJavaCompiler {

  private TestJavaCompiler() {}

  /**
   * Compiles one or more top-level source files (keyed by fully-qualified class name) and returns
   * every resulting {@code .class} file's bytes, keyed by internal name (slashes). {@code
   * withParameters} controls {@code javac -parameters}.
   */
  static Map<String, byte[]> compile(boolean withParameters, Map<String, String> sourcesByFqcn) {
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    if (compiler == null) {
      throw new IllegalStateException(
          "No system Java compiler available (running on a JRE, not a JDK?).");
    }
    try {
      Path sourceDir = Files.createTempDirectory("turnbase-test-src-");
      Path outDir = Files.createTempDirectory("turnbase-test-out-");
      List<Path> sourceFiles = new java.util.ArrayList<>();
      for (Map.Entry<String, String> entry : sourcesByFqcn.entrySet()) {
        String relative = entry.getKey().replace('.', '/') + ".java";
        Path sourceFile = sourceDir.resolve(relative);
        Files.createDirectories(sourceFile.getParent());
        Files.writeString(sourceFile, entry.getValue());
        sourceFiles.add(sourceFile);
      }
      List<String> options =
          withParameters
              ? List.of("-d", outDir.toString(), "-parameters")
              : List.of("-d", outDir.toString());
      List<String> args = new java.util.ArrayList<>(options);
      sourceFiles.forEach(f -> args.add(f.toString()));
      java.io.ByteArrayOutputStream errors = new java.io.ByteArrayOutputStream();
      int result = compiler.run(null, null, errors, args.toArray(new String[0]));
      if (result != 0) {
        throw new IllegalStateException(
            "Compilation failed:\n" + errors.toString(java.nio.charset.StandardCharsets.UTF_8));
      }

      Map<String, byte[]> classes = new LinkedHashMap<>();
      try (Stream<Path> walk = Files.walk(outDir)) {
        walk.filter(p -> p.toString().endsWith(".class"))
            .forEach(
                p -> {
                  String internalName =
                      outDir
                          .relativize(p)
                          .toString()
                          .replace('\\', '/')
                          .replaceAll("\\.class$", "");
                  try {
                    classes.put(internalName, Files.readAllBytes(p));
                  } catch (IOException e) {
                    throw new UncheckedIOException(e);
                  }
                });
      }
      return classes;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
