package io.turnbase.platform.common;

import java.text.Normalizer;

/**
 * input-validation.md § Canonicalization — applied to every user-supplied string before any length
 * check, comparison, or persistence: UTF-8 well-formedness is already enforced by the HTTP message
 * converter before a value reaches this class, so what remains is NFC normalization, trimming, and
 * (left to the caller, since the response must name the offending field) forbidden-code-point
 * rejection.
 */
public final class Canonicalizer {

  private Canonicalizer() {}

  /** NFC-normalizes and trims leading/trailing whitespace. */
  public static String canonicalize(String raw) {
    return Normalizer.normalize(raw, Normalizer.Form.NFC).strip();
  }

  /**
   * The forbidden-code-point table: C0/C1 controls, {@code U+007F}, bidi controls (the Trojan
   * Source class of attack), and surrogates/private-use/unassigned code points. Fields that permit
   * newlines check for that exception themselves — no field in this item does.
   */
  public static boolean hasForbiddenCodePoint(String s) {
    return s.codePoints().anyMatch(Canonicalizer::isForbidden);
  }

  private static boolean isForbidden(int codePoint) {
    if (codePoint <= 0x1F || codePoint == 0x7F) {
      return true;
    }
    if (codePoint >= 0x80 && codePoint <= 0x9F) {
      return true;
    }
    if (codePoint >= 0x202A && codePoint <= 0x202E) {
      return true;
    }
    if (codePoint >= 0x2066 && codePoint <= 0x2069) {
      return true;
    }
    int type = Character.getType(codePoint);
    return type == Character.SURROGATE
        || type == Character.PRIVATE_USE
        || type == Character.UNASSIGNED;
  }
}
