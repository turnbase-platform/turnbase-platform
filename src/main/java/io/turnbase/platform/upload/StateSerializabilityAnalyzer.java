package io.turnbase.platform.upload;

import io.turnbase.platform.common.CheckError;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.RecordComponentNode;
import org.springframework.stereotype.Component;

/**
 * {@code bundle-format.md} §4.4's static serializability analysis (F6/F7) — resolves the {@code S}
 * type argument of the entry class's {@code TurnBaseGame<S>} supertype from its generic {@code
 * Signature} attribute, then walks {@code S}'s field/record-component graph against the table in
 * §4.4.
 *
 * <p>{@code game-host-internals.md} §5's shared serialization component does not exist as a
 * consumable artifact yet (it is out of this item's read-list) — the module-registry knowledge §4.4
 * asks this stage to read from that component (which {@code java.time.*}/{@code Optional} types a
 * registered Jackson module would handle) is hardcoded here instead, matching the "read from the
 * shared serialization component... never re-derived" intent as closely as possible without a real
 * artifact to read it from. See the IMP-M4-03 session report.
 */
@Component
class StateSerializabilityAnalyzer {

  private static final String TURNBASE_GAME_INTERNAL_NAME = "io/turnbase/gdk/TurnBaseGame";
  private static final Pattern STATE_TYPE_ARG =
      Pattern.compile("Lio/turnbase/gdk/TurnBaseGame<L([^;<>]+);>;");
  private static final String JSON_PROPERTY_DESC =
      "Lcom/fasterxml/jackson/annotation/JsonProperty;";

  private static final Set<String> SAFE_LEAF_TYPES =
      Set.of(
          "java/lang/String",
          "java/util/UUID",
          "java/lang/Integer",
          "java/lang/Long",
          "java/lang/Short",
          "java/lang/Byte",
          "java/lang/Double",
          "java/lang/Float",
          "java/lang/Boolean",
          "java/lang/Character");
  private static final Set<String> MAP_KEY_SAFE_TYPES =
      Set.of(
          "java/lang/String",
          "java/util/UUID",
          "java/lang/Integer",
          "java/lang/Long",
          "java/lang/Short",
          "java/lang/Byte");
  private static final Set<String> MAP_TYPES =
      Set.of(
          "java/util/Map",
          "java/util/HashMap",
          "java/util/LinkedHashMap",
          "java/util/TreeMap",
          "java/util/SortedMap");
  private static final Set<String> COLLECTION_TYPES =
      Set.of(
          "java/util/List",
          "java/util/Set",
          "java/util/Collection",
          "java/util/ArrayList",
          "java/util/LinkedList",
          "java/util/HashSet",
          "java/util/LinkedHashSet",
          "java/util/TreeSet");

  private static final int MAX_GRAPH_TYPES = 2000;
  private static final int MAX_GRAPH_DEPTH = 64;

  record Result(List<CheckError> errors, List<CheckError> warnings) {
    static final Result EMPTY = new Result(List.of(), List.of());
  }

  /**
   * Stage F6. Returns empty when {@code S} is not statically resolvable — raw usage or a type
   * variable — in which case the caller records the {@code state.type} warning and skips F7.
   */
  @Nullable String resolveStateType(ClassNode entryClass, ArchiveReadResult archive) {
    ClassNode current = entryClass;
    int guard = 0;
    while (current != null && guard++ < 32) {
      String signature = current.signature;
      if (signature != null) {
        Matcher matcher = STATE_TYPE_ARG.matcher(signature);
        if (matcher.find()) {
          return matcher.group(1);
        }
      }
      if (current.interfaces != null
          && current.interfaces.contains(TURNBASE_GAME_INTERNAL_NAME)
          && signature == null) {
        return null; // implements TurnBaseGame raw at this level — no generic argument to read.
      }
      String superName = current.superName;
      current =
          (superName == null || "java/lang/Object".equals(superName))
              ? null
              : archive
                  .find(superName + ".class")
                  .map(e -> ClassFileUtil.readClassNode(e.data()))
                  .orElse(null);
    }
    return null;
  }

  /** Stage F7: walks {@code S}'s reachable type graph against §4.4's table. */
  Result analyze(String stateInternalName, ArchiveReadResult archive) {
    Walker walker = new Walker(archive);
    walker.walk(stateInternalName, new ArrayDeque<>(), 0);
    return new Result(List.copyOf(walker.errors), List.copyOf(walker.warnings));
  }

  private static final class Walker {
    private final ArchiveReadResult archive;
    private final Set<String> fullyProcessed = new HashSet<>();
    private final List<CheckError> errors = new ArrayList<>();
    private final List<CheckError> warnings = new ArrayList<>();
    private int typeCount = 0;
    private boolean truncated = false;

    Walker(ArchiveReadResult archive) {
      this.archive = archive;
    }

    void walk(String internalName, Deque<String> path, int depth) {
      if (truncated) {
        return;
      }
      if (typeCount >= MAX_GRAPH_TYPES || depth > MAX_GRAPH_DEPTH) {
        truncated = true;
        warnings.add(
            new CheckError(
                "state.serializable",
                "The state type graph exceeds the analysis bound (2000 types or depth 64);"
                    + " analysis was truncated."));
        return;
      }
      if (path.contains(internalName)) {
        warnings.add(
            new CheckError(
                "state.serializable", "Reference cycle detected through '" + internalName + "'."));
        return;
      }
      if (fullyProcessed.contains(internalName)) {
        return;
      }
      var entryOpt = archive.find(internalName + ".class");
      if (entryOpt.isEmpty()) {
        warnings.add(
            new CheckError(
                "state.serializable",
                "Type '" + internalName + "' could not be statically resolved for analysis."));
        return;
      }
      typeCount++;
      fullyProcessed.add(internalName);
      ClassNode node = ClassFileUtil.readClassNode(entryOpt.get().data());

      if ((node.access & Opcodes.ACC_ENUM) != 0) {
        return; // Jackson serializes enums by name; not subject to the constructor rule.
      }

      path.addLast(internalName);
      boolean isRecord = node.recordComponents != null;
      if (!isRecord) {
        checkConstructorRule(node);
      }

      if (isRecord) {
        for (RecordComponentNode component : node.recordComponents) {
          walkFieldLikeType(component.name, component.descriptor, component.signature, path, depth);
        }
      } else {
        for (FieldNode field : node.fields) {
          if ((field.access & (Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC)) != 0) {
            continue;
          }
          walkFieldLikeType(field.name, field.desc, field.signature, path, depth);
        }
      }
      path.removeLast();
    }

    private void checkConstructorRule(ClassNode node) {
      boolean hasNoArgCtor =
          node.methods.stream().anyMatch(m -> "<init>".equals(m.name) && "()V".equals(m.desc));
      if (hasNoArgCtor) {
        return;
      }
      boolean anyCtorNamesParameters =
          node.methods.stream()
              .filter(m -> "<init>".equals(m.name))
              .anyMatch(this::ctorNamesAllParameters);
      if (!anyCtorNamesParameters) {
        errors.add(
            new CheckError(
                "state.serializable",
                "'"
                    + node.name.replace('/', '.')
                    + "' is not a record, has no no-argument"
                    + " constructor, and its constructor parameters carry neither a"
                    + " MethodParameters attribute (compile with -parameters) nor @JsonProperty."));
      }
    }

    private boolean ctorNamesAllParameters(MethodNode ctor) {
      Type[] argTypes = Type.getArgumentTypes(ctor.desc);
      if (argTypes.length == 0) {
        return true;
      }
      if (ctor.parameters != null && ctor.parameters.size() == argTypes.length) {
        return true;
      }
      List<AnnotationNode>[] paramAnnotations = ctor.visibleParameterAnnotations;
      if (paramAnnotations == null || paramAnnotations.length < argTypes.length) {
        return false;
      }
      for (int i = 0; i < argTypes.length; i++) {
        List<AnnotationNode> annotations = paramAnnotations[i];
        boolean hasJsonProperty =
            annotations != null
                && annotations.stream().anyMatch(a -> JSON_PROPERTY_DESC.equals(a.desc));
        if (!hasJsonProperty) {
          return false;
        }
      }
      return true;
    }

    private void walkFieldLikeType(
        String fieldName,
        String descriptor,
        @Nullable String signature,
        Deque<String> path,
        int depth) {
      Type type = Type.getType(descriptor);
      classify(fieldName, type, signature, path, depth);
    }

    private void classify(
        String fieldName, Type type, @Nullable String signature, Deque<String> path, int depth) {
      switch (type.getSort()) {
        case Type.ARRAY -> classify(fieldName, type.getElementType(), null, path, depth + 1);
        case Type.OBJECT ->
            classifyObject(fieldName, type.getInternalName(), signature, path, depth);
        default -> {
          // Primitive: always a safe leaf.
        }
      }
    }

    private void classifyObject(
        String fieldName,
        String internalName,
        @Nullable String signature,
        Deque<String> path,
        int depth) {
      if (MAP_TYPES.contains(internalName)) {
        List<String> args = topLevelTypeArgs(signature);
        if (args.size() != 2) {
          errors.add(
              new CheckError(
                  "state.serializable",
                  "Field '" + fieldName + "' is a raw Map with no statically known key type."));
          return;
        }
        checkMapKey(fieldName, args.get(0));
        recurseIntoTypeArg(args.get(1), path, depth);
        return;
      }
      if (COLLECTION_TYPES.contains(internalName)) {
        List<String> args = topLevelTypeArgs(signature);
        if (args.size() == 1) {
          recurseIntoTypeArg(args.get(0), path, depth);
        }
        return;
      }
      if ("java/lang/Object".equals(internalName)) {
        errors.add(
            new CheckError(
                "state.serializable", "Field '" + fieldName + "' is declared as Object."));
        return;
      }
      if (internalName.startsWith("java/time/") || "java/util/Optional".equals(internalName)) {
        errors.add(
            new CheckError(
                "state.serializable",
                "Field '"
                    + fieldName
                    + "' has type '"
                    + internalName.replace('/', '.')
                    + "', which no registered Jackson module handles."));
        return;
      }
      if (SAFE_LEAF_TYPES.contains(internalName)) {
        return;
      }

      var classEntry = archive.find(internalName + ".class");
      if (classEntry.isPresent()) {
        ClassNode node = ClassFileUtil.readClassNode(classEntry.get().data());
        boolean isEnum = (node.access & Opcodes.ACC_ENUM) != 0;
        boolean isInterfaceOrAbstract =
            !isEnum
                && ((node.access & Opcodes.ACC_INTERFACE) != 0
                    || (node.access & Opcodes.ACC_ABSTRACT) != 0);
        if (isInterfaceOrAbstract) {
          errors.add(
              new CheckError(
                  "state.serializable",
                  "Field '"
                      + fieldName
                      + "' is declared as interface/abstract type '"
                      + internalName.replace('/', '.')
                      + "'."));
          return;
        }
      }
      walk(internalName, path, depth + 1);
    }

    private void checkMapKey(String fieldName, String keyArg) {
      String keyInternalName = stripTypeArg(keyArg);
      if (keyInternalName == null) {
        errors.add(
            new CheckError(
                "state.serializable",
                "Field '" + fieldName + "' has an unresolvable Map key type."));
        return;
      }
      if (MAP_KEY_SAFE_TYPES.contains(keyInternalName)) {
        return;
      }
      var classEntry = archive.find(keyInternalName + ".class");
      if (classEntry.isPresent()) {
        ClassNode node = ClassFileUtil.readClassNode(classEntry.get().data());
        if ((node.access & Opcodes.ACC_ENUM) != 0) {
          return;
        }
      }
      errors.add(
          new CheckError(
              "state.serializable",
              "Field '"
                  + fieldName
                  + "' is a Map whose key type '"
                  + keyInternalName.replace('/', '.')
                  + "' is not String, an enum, UUID or a boxed integral type."));
    }

    private void recurseIntoTypeArg(String typeArg, Deque<String> path, int depth) {
      if (typeArg.startsWith("[")) {
        classify("<element>", Type.getType(typeArg), null, path, depth + 1);
        return;
      }
      String internalName = stripTypeArg(typeArg);
      if (internalName == null) {
        warnings.add(
            new CheckError("state.serializable", "Unresolvable element type '" + typeArg + "'."));
        return;
      }
      classifyObject("<element>", internalName, null, path, depth + 1);
    }

    private @Nullable String stripTypeArg(String arg) {
      if (arg.startsWith("L") && arg.endsWith(";")) {
        return arg.substring(1, arg.length() - 1);
      }
      return null; // type variable ('T...;') or wildcard — not statically resolvable here.
    }

    /**
     * Extracts the top-level type arguments of a single generic type usage, e.g. from {@code
     * "Ljava/util/Map<Lfoo/K;Lfoo/V;>;"} returns {@code ["Lfoo/K;", "Lfoo/V;"]}. Empty for a raw
     * (non-generic) usage or a null signature.
     */
    private List<String> topLevelTypeArgs(@Nullable String signature) {
      if (signature == null) {
        return List.of();
      }
      int lt = signature.indexOf('<');
      if (lt < 0) {
        return List.of();
      }
      List<String> args = new ArrayList<>();
      StringBuilder current = new StringBuilder();
      int depth = 0;
      for (int i = lt + 1; i < signature.length(); i++) {
        char c = signature.charAt(i);
        if (c == '<') {
          depth++;
          current.append(c);
        } else if (c == '>') {
          if (depth == 0) {
            break;
          }
          depth--;
          current.append(c);
        } else if (c == ';' && depth == 0) {
          current.append(c);
          args.add(current.toString());
          current.setLength(0);
        } else {
          current.append(c);
        }
      }
      return args;
    }
  }
}
