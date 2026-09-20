package io.turnbase.platform.upload;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * A minimal, real {@code runtime: "jvm"} game bundle ("Chess") for tests that need the pipeline to
 * run past Phase F — shared by every IT that exercises a full upload, public or admin listener
 * alike, rather than each rebuilding the same fixture.
 */
public final class TestBundles {

  private TestBundles() {}

  public static byte[] validChessBundle(String version) {
    Map<String, byte[]> classes = compileChessClasses();
    byte[] backendJar =
        zipOf(
            Map.of(
                "com/example/ChessGame.class",
                classes.get("com/example/ChessGame"),
                "com/example/ChessState.class",
                classes.get("com/example/ChessState")));
    String manifest = chessManifest(version, "frontend/main.js");
    return zipOf(
        Map.of(
            "manifest.json", manifest.getBytes(UTF_8),
            "backend/game.jar", backendJar,
            "frontend/main.js", "console.log('hi');".getBytes(UTF_8)));
  }

  public static byte[] chessBundleMissingFrontendEntry(String version) {
    Map<String, byte[]> classes = compileChessClasses();
    byte[] backendJar =
        zipOf(
            Map.of(
                "com/example/ChessGame.class",
                classes.get("com/example/ChessGame"),
                "com/example/ChessState.class",
                classes.get("com/example/ChessState")));
    String manifest = chessManifest(version, "frontend/does-not-exist.js");
    return zipOf(Map.of("manifest.json", manifest.getBytes(UTF_8), "backend/game.jar", backendJar));
  }

  private static Map<String, byte[]> compileChessClasses() {
    return TestJavaCompiler.compile(
        true,
        Map.of(
            "io.turnbase.gdk.TurnBaseGame",
                "package io.turnbase.gdk; public interface TurnBaseGame<S> {}",
            "com.example.ChessState",
                "package com.example; public record ChessState(String board) {}",
            "com.example.ChessGame",
                "package com.example; public final class ChessGame implements io.turnbase.gdk.TurnBaseGame<ChessState> {"
                    + " public ChessGame() {} }"));
  }

  private static String chessManifest(String version, String frontendEntryPoint) {
    return """
        {
          "manifestVersion":"1",
          "title":"Chess",
          "shortDescription":"The classic game",
          "version":"%s",
          "gdkApiVersion":"0.3.0",
          "runtime":"jvm",
          "backend":{"path":"backend/game.jar","entryPoint":"com.example.ChessGame"},
          "frontend":{"entryPoint":"%s"},
          "tags":["strategy"],
          "capabilities":{"ai":false,"spectators":false,"midGameJoin":false},
          "teamTemplates":[{"size":{"min":1,"max":1},"count":{"min":2,"max":2}}]
        }
        """
        .formatted(version, frontendEntryPoint);
  }

  private static byte[] zipOf(Map<String, byte[]> entries) {
    try {
      ByteArrayOutputStream buffer = new ByteArrayOutputStream();
      try (ZipOutputStream zos = new ZipOutputStream(buffer, UTF_8)) {
        for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
          zos.putNextEntry(new ZipEntry(entry.getKey()));
          zos.write(entry.getValue());
          zos.closeEntry();
        }
      }
      return buffer.toByteArray();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
