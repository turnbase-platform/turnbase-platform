package io.turnbase.platform.upload;

import io.turnbase.platform.common.CheckError;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.springframework.stereotype.Component;

/**
 * {@code bundle-format.md} §3.2 Phase F, {@code runtime: "jvm"} only. F1-F2 are a barrier for F3-F7
 * (§3.3) — this class stops at the first failing stage in that pair, matching "a failing E1/E2
 * skips F entirely" one level down.
 */
@Component
class BackendJarAnalyzer {

  private static final String GDK_PACKAGE_PREFIX = "io/turnbase/gdk/";
  private static final String TURNBASE_GAME_INTERNAL_NAME = "io/turnbase/gdk/TurnBaseGame";
  private static final String JACKSON_PACKAGE_PREFIX = "com/fasterxml/jackson/";
  private static final int MAX_CLASS_FILE_MAJOR = 65; // Java 21
  private static final int MAX_MULTI_RELEASE_VERSION = 21;

  private final ArchiveReader archiveReader;

  BackendJarAnalyzer(ArchiveReader archiveReader) {
    this.archiveReader = archiveReader;
  }

  record Result(
      List<CheckError> errors,
      List<CheckError> warnings,
      @Nullable ArchiveReadResult innerArchive,
      @Nullable String entryClassInternalName) {}

  Result analyze(byte[] backendJarBytes, String entryPointClassName, ArchiveReader.Limits limits) {
    // F1: the entry at backend.path is itself a readable ZIP/JAR, subject to §2.2-2.3 recursively.
    ArchiveReadResult inner = archiveReader.read(backendJarBytes, limits);
    if (!inner.ok()) {
      return new Result(
          List.copyOf(
              inner.errors().stream()
                  .map(e -> new CheckError("backend.jar", e.message()))
                  .toList()),
          List.of(),
          null,
          null);
    }

    // F2: every .class entry has major version <= 65; META-INF/versions/N/ entries need N <= 21.
    List<CheckError> f2Errors = new ArrayList<>();
    for (ArchiveEntry entry : inner.entries()) {
      if (entry.directory() || !entry.name().endsWith(".class")) {
        continue;
      }
      String name = entry.name();
      if (name.startsWith("META-INF/versions/")) {
        String[] parts = name.split("/", 4);
        int release = parts.length > 2 ? parseIntOrZero(parts[2]) : 0;
        if (release > MAX_MULTI_RELEASE_VERSION) {
          f2Errors.add(
              new CheckError(
                  "backend.classVersion",
                  "'" + name + "' targets release " + release + ", exceeding Java 21."));
          continue;
        }
      }
      if (entry.data().length < 8) {
        f2Errors.add(
            new CheckError(
                "backend.classVersion", "'" + name + "' is not a valid class file (too short)."));
        continue;
      }
      int major = ClassFileUtil.classFileMajorVersion(entry.data());
      if (major > MAX_CLASS_FILE_MAJOR) {
        f2Errors.add(
            new CheckError(
                "backend.classVersion",
                "'"
                    + name
                    + "' has class file major version "
                    + major
                    + ", exceeding Java 21's 65."));
      }
    }
    if (!f2Errors.isEmpty()) {
      return new Result(f2Errors, List.of(), null, null);
    }

    List<CheckError> errors = new ArrayList<>();
    List<CheckError> warnings = new ArrayList<>();

    // F3: no entry under io/turnbase/gdk/.
    long gdkEntries =
        inner.entries().stream()
            .filter(e -> !e.directory() && e.name().startsWith(GDK_PACKAGE_PREFIX))
            .count();
    if (gdkEntries > 0) {
      errors.add(
          new CheckError(
              "backend.gdkClasses",
              "JAR contains %d entries under io/turnbase/gdk/. Declare the GDK API as 'provided' scope."
                  .formatted(gdkEntries)));
    }

    // F4: warning if shading a package the guest bridge also provides.
    boolean shadesJackson =
        inner.entries().stream()
            .anyMatch(e -> !e.directory() && e.name().startsWith(JACKSON_PACKAGE_PREFIX));
    if (shadesJackson) {
      warnings.add(
          new CheckError(
              "backend.shadedRuntime",
              "JAR shades com/fasterxml/jackson/, which the guest bridge also provides on the"
                  + " same classpath; classloader order decides which wins."));
    }

    // F5: backend.entryPoint resolves to a public, non-abstract class with a public no-arg
    // constructor, implementing io.turnbase.gdk.TurnBaseGame.
    String entryInternalName = entryPointClassName.replace('.', '/');
    var entryEntry = inner.find(entryInternalName + ".class");
    if (entryEntry.isEmpty()) {
      errors.add(
          new CheckError(
              "backend.entryPoint",
              "Entry point class '" + entryPointClassName + "' was not found in the JAR."));
      return new Result(errors, warnings, inner, null);
    }

    ClassNode classNode = ClassFileUtil.readClassNode(entryEntry.get().data());
    if ((classNode.access & Opcodes.ACC_PUBLIC) == 0) {
      errors.add(new CheckError("backend.entryPoint", "Entry point class is not public."));
    }
    if ((classNode.access & Opcodes.ACC_ABSTRACT) != 0) {
      errors.add(new CheckError("backend.entryPoint", "Entry point class is abstract."));
    }
    if (!hasPublicNoArgConstructor(classNode)) {
      errors.add(
          new CheckError(
              "backend.entryPoint", "Entry point class has no public no-argument constructor."));
    }
    if (!implementsTurnBaseGame(classNode, inner)) {
      errors.add(
          new CheckError(
              "backend.entryPoint",
              "Entry point class does not implement io.turnbase.gdk.TurnBaseGame."));
    }

    return new Result(errors, warnings, inner, entryInternalName);
  }

  private boolean implementsTurnBaseGame(ClassNode start, ArchiveReadResult archive) {
    ClassNode current = start;
    int guard = 0;
    while (current != null && guard++ < 32) {
      if (current.interfaces != null && current.interfaces.contains(TURNBASE_GAME_INTERNAL_NAME)) {
        return true;
      }
      String superName = current.superName;
      if (superName == null || "java/lang/Object".equals(superName)) {
        return false;
      }
      var superEntry = archive.find(superName + ".class");
      current = superEntry.map(e -> ClassFileUtil.readClassNode(e.data())).orElse(null);
    }
    return false;
  }

  private boolean hasPublicNoArgConstructor(ClassNode classNode) {
    for (MethodNode method : classNode.methods) {
      if ("<init>".equals(method.name)
          && "()V".equals(method.desc)
          && (method.access & Opcodes.ACC_PUBLIC) != 0) {
        return true;
      }
    }
    return false;
  }

  private static int parseIntOrZero(String value) {
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException e) {
      return 0;
    }
  }
}
