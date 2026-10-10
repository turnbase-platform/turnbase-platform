package io.turnbase.platform.common;

import java.util.Locale;

/**
 * Builds {@code LIKE} patterns from user text. {@code %}, {@code _} and the escape character itself
 * are escaped, so the match is over the user's literal text; the query must say {@code ESCAPE '\'}.
 */
public final class LikePattern {

  private LikePattern() {}

  /** A lower-cased, literal substring pattern: {@code %text%}. */
  public static String containsLowerCase(String text) {
    String escaped =
        text.toLowerCase(Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    return "%" + escaped + "%";
  }
}
