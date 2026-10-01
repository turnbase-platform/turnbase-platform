package io.turnbase.platform.common;

import net.logstash.logback.argument.StructuredArguments;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * One INFO line per lifecycle transition (MR-06, {@code observability.md} §3). Each field is both a
 * top-level JSON key — so Loki can filter on {@code roomId} or {@code userId} — and a {@code
 * name=value} pair in the message. Absent (null) fields are omitted rather than written as {@code
 * null}, matching FR-OBS-003. Callers pass identifiers and enumerated reasons only: never a
 * credential, a session token, or chat text.
 */
public final class LifecycleLog {

  private LifecycleLog() {}

  /**
   * Logs {@code message} at INFO followed by the non-null fields.
   *
   * @param fields alternating {@code String} name and {@code @Nullable Object} value
   */
  public static void info(Logger log, String message, @Nullable Object... fields) {
    if (!log.isInfoEnabled()) {
      return;
    }
    StringBuilder format = new StringBuilder(message);
    Object[] args = new Object[fields.length / 2];
    int count = 0;
    for (int i = 0; i + 1 < fields.length; i += 2) {
      Object value = fields[i + 1];
      if (value == null) {
        continue;
      }
      format.append(" {}");
      args[count++] = StructuredArguments.kv((String) fields[i], value);
    }
    log.info(format.toString(), java.util.Arrays.copyOf(args, count));
  }
}
