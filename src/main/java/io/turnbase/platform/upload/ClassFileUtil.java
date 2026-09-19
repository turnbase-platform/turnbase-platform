package io.turnbase.platform.upload;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;

/**
 * Small ASM helpers shared by {@link BackendJarAnalyzer} (F1-F5) and {@link
 * StateSerializabilityAnalyzer} (F6-F7) — static bytecode inspection only, per {@code
 * NFR-UPLOAD-002}: nothing here ever classloads or executes archive bytes.
 */
final class ClassFileUtil {

  private ClassFileUtil() {}

  /**
   * {@code SKIP_CODE} is safe here: every check this pipeline runs reads structural metadata
   * (flags, supertypes, the generic {@code Signature} attribute, {@code MethodParameters},
   * parameter annotations) rather than method bodies.
   */
  static ClassNode readClassNode(byte[] classBytes) {
    ClassReader reader = new ClassReader(classBytes);
    ClassNode node = new ClassNode();
    reader.accept(node, ClassReader.SKIP_FRAMES | ClassReader.SKIP_CODE);
    return node;
  }

  static int classFileMajorVersion(byte[] classBytes) {
    // Bytes 0-3 are the 0xCAFEBABE magic; bytes 4-5 minor, 6-7 major (big-endian).
    return ((classBytes[6] & 0xFF) << 8) | (classBytes[7] & 0xFF);
  }
}
