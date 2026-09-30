package io.turnbase.platform.upload;

import io.turnbase.bundle.VersionOrdering;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code bundle-format.md} §6.1/§8: {@code turnbase.gdk.supported-range}, an {@code x.y.z-x.y.z}
 * closed interval evaluated with the same three-integer comparison as {@code FR-GAME-009}.
 *
 * <p>§6.1 specifies this as an assertion checked against the deployed guest bridge JAR's own
 * declared GDK version at startup, failing startup on mismatch. No guest bridge or Game Host client
 * exists in this repository yet (IMP-M4-03's read-list excludes {@code game-host-internals.md}), so
 * there is nothing to assert the configured value against — this class only parses and exposes the
 * configured range for stage E5 to check the manifest's {@code gdkApiVersion} against. See
 * IMP-M4-03's session report for this scope simplification.
 */
@ConfigurationProperties(prefix = "turnbase.gdk")
public record GdkCompatibilityProperties(String supportedRange) {

  /** Parses {@code "0.3.0-0.3.0"} into its low/high {@link VersionOrdering.Version} bounds. */
  public Range range() {
    String[] parts = splitRange(supportedRange);
    return new Range(
        VersionOrdering.Version.parse(parts[0]), VersionOrdering.Version.parse(parts[1]));
  }

  private static String[] splitRange(String value) {
    // Each bound is exactly three dot-separated integers; the range is two bounds joined by '-'.
    // Find the '-' that sits between the third integer of the low bound and the first of the
    // high bound by counting dots.
    int dots = 0;
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c == '.') {
        dots++;
      } else if (c == '-' && dots == 2) {
        return new String[] {value.substring(0, i), value.substring(i + 1)};
      }
    }
    throw new IllegalArgumentException(
        "turnbase.gdk.supported-range must be 'x.y.z-x.y.z', got: " + value);
  }

  public record Range(VersionOrdering.Version low, VersionOrdering.Version high) {

    public boolean contains(VersionOrdering.Version version) {
      return VersionOrdering.compare(version, low) >= 0
          && VersionOrdering.compare(version, high) <= 0;
    }
  }
}
