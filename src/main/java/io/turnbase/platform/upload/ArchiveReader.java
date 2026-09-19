package io.turnbase.platform.upload;

import io.turnbase.platform.common.CheckError;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * {@code bundle-format.md} §2 — the single ZIP reader used by validation, publication, and (one
 * level deep, per F1) the backend JAR analyzer. Reads the central directory directly rather than
 * through {@code java.util.zip.ZipFile}: the general-purpose bit flags that decide UTF-8 encoding
 * (bit 11) and encryption (bit 0), and the local-header cross-check of §2.2, are not exposed by
 * {@code java.util.zip}'s API.
 *
 * <p>Does not support Zip64 — the compressed-size cap (256 MB) and entry-count cap (20 000) are
 * both far below the thresholds that would ever require it, so a Zip64 end-of-central-directory
 * marker is treated as unsupported and rejected under {@code bundle.archive.format}.
 */
@Component
class ArchiveReader {

  private static final int EOCD_SIGNATURE = 0x06054b50;
  private static final int CDFH_SIGNATURE = 0x02014b50;
  private static final int LFH_SIGNATURE = 0x04034b50;
  private static final int GP_ENCRYPTED_BIT = 0x0001;
  private static final int GP_DATA_DESCRIPTOR_BIT = 0x0008;
  private static final int GP_UTF8_BIT = 0x0800;
  private static final int METHOD_STORED = 0;
  private static final int METHOD_DEFLATED = 8;
  private static final int MAX_NAME_BYTES = 1024;
  private static final int MAX_SEGMENT_BYTES = 255;
  private static final int MAX_DEPTH = 32;
  private static final int RATIO_FREE_BYTES = 1_000_000;

  record Limits(
      long maxTotalUncompressed, long maxEntryUncompressed, int maxRatio, int maxEntryCount) {}

  ArchiveReadResult read(byte[] zipBytes, Limits limits) {
    try {
      return readInternal(zipBytes, limits);
    } catch (CeilingBreachException e) {
      return new ArchiveReadResult(
          List.of(new CheckError("bundle.archive.expansion", String.valueOf(e.getMessage()))),
          List.of());
    } catch (ArchiveFormatException e) {
      return new ArchiveReadResult(
          List.of(new CheckError("bundle.archive.format", String.valueOf(e.getMessage()))),
          List.of());
    }
  }

  private static final class ArchiveFormatException extends RuntimeException {
    ArchiveFormatException(String message) {
      super(message);
    }
  }

  /**
   * §2.3: a resource ceiling breached during inflation — distinct from {@link
   * ArchiveFormatException} because it reports under {@code bundle.archive.expansion}, not {@code
   * bundle.archive.format}.
   */
  private static final class CeilingBreachException extends RuntimeException {
    CeilingBreachException(String message) {
      super(message);
    }
  }

  private ArchiveReadResult readInternal(byte[] zip, Limits limits) {
    ByteView view = new ByteView(zip);
    int eocd = findEndOfCentralDirectory(view);
    int totalEntries = view.u16(eocd + 10);
    long cdOffset = view.u32(eocd + 16);
    if (totalEntries == 0xFFFF || cdOffset == 0xFFFFFFFFL) {
      throw new ArchiveFormatException("Zip64 archives are not supported.");
    }
    if (totalEntries > limits.maxEntryCount()) {
      throw new ArchiveFormatException(
          "Archive contains %d entries, exceeding the %d-entry limit."
              .formatted(totalEntries, limits.maxEntryCount()));
    }

    List<RawEntry> rawEntries = new ArrayList<>();
    long pointer = cdOffset;
    List<String> structuralProblems = new ArrayList<>();
    for (int i = 0; i < totalEntries; i++) {
      if (pointer + 46 > zip.length || view.u32((int) pointer) != CDFH_SIGNATURE) {
        throw new ArchiveFormatException("Central directory is truncated or corrupt.");
      }
      int cd = (int) pointer;
      int versionMadeBy = view.u16(cd + 4);
      int gpFlags = view.u16(cd + 8);
      int method = view.u16(cd + 10);
      long compressedSize = view.u32(cd + 20);
      long uncompressedSize = view.u32(cd + 24);
      int nameLen = view.u16(cd + 28);
      int extraLen = view.u16(cd + 30);
      int commentLen = view.u16(cd + 32);
      long externalAttrs = view.u32(cd + 38);
      long localHeaderOffset = view.u32(cd + 42);
      byte[] nameBytes = view.slice(cd + 46, nameLen);

      if ((gpFlags & GP_ENCRYPTED_BIT) != 0) {
        structuralProblems.add("entry is encrypted");
      }
      if (method != METHOD_STORED && method != METHOD_DEFLATED) {
        structuralProblems.add("entry uses unsupported compression method " + method);
      }

      boolean utf8 = (gpFlags & GP_UTF8_BIT) != 0;
      if (!utf8 && !isPureAscii(nameBytes)) {
        structuralProblems.add("entry name is not UTF-8 encoded and not pure ASCII");
      }
      String name = new String(nameBytes, StandardCharsets.UTF_8);

      boolean localHeaderOk =
          crossCheckLocalHeader(view, localHeaderOffset, name, method, compressedSize, gpFlags);
      if (!localHeaderOk) {
        structuralProblems.add(
            "local header for '" + name + "' disagrees with the central directory");
      }

      Integer unixFileType = null;
      if ((versionMadeBy >>> 8) == 3) {
        unixFileType = (int) ((externalAttrs >>> 16) & 0xF000);
      }
      boolean directory = name.endsWith("/");

      rawEntries.add(
          new RawEntry(
              name,
              directory,
              method,
              compressedSize,
              uncompressedSize,
              localHeaderOffset,
              unixFileType));

      pointer = cd + 46L + nameLen + extraLen + commentLen;
    }

    if (!structuralProblems.isEmpty()) {
      throw new ArchiveFormatException(
          "Archive fails structural checks: "
              + String.join("; ", structuralProblems.stream().distinct().toList()));
    }

    List<CheckError> errors = new ArrayList<>();
    for (RawEntry entry : rawEntries) {
      String problem = validateName(entry.name());
      if (problem != null) {
        errors.add(
            new CheckError("bundle.archive.entryName", "'" + entry.name() + "': " + problem));
      }
      Integer unixFileType = entry.unixFileType();
      if (!entry.directory() && unixFileType != null) {
        int type = unixFileType;
        if (type != 0x8000 && type != 0 && type != 0x4000) {
          errors.add(
              new CheckError(
                  "bundle.archive.entryType",
                  "'" + entry.name() + "' is not a regular file or directory."));
        }
      }
    }

    Map<String, List<String>> byLowerName = new TreeMap<>();
    for (RawEntry entry : rawEntries) {
      byLowerName
          .computeIfAbsent(entry.name().toLowerCase(java.util.Locale.ROOT), k -> new ArrayList<>())
          .add(entry.name());
    }
    for (Map.Entry<String, List<String>> group : byLowerName.entrySet()) {
      if (group.getValue().size() > 1) {
        errors.add(
            new CheckError(
                "bundle.archive.duplicate",
                "Entries "
                    + group.getValue()
                    + " collide case-insensitively as '"
                    + group.getKey()
                    + "'."));
      }
    }

    if (!errors.isEmpty()) {
      return new ArchiveReadResult(errors, List.of());
    }

    return inflateAll(view, rawEntries, limits);
  }

  private ArchiveReadResult inflateAll(ByteView view, List<RawEntry> rawEntries, Limits limits) {
    List<ArchiveEntry> result = new ArrayList<>();
    long totalUncompressed = 0;
    for (RawEntry entry : rawEntries) {
      if (entry.directory()) {
        result.add(new ArchiveEntry(entry.name(), true, 0, new byte[0]));
        continue;
      }
      int dataStart = localDataStart(view, entry.localHeaderOffset());
      byte[] compressed = view.slice(dataStart, (int) entry.compressedSize());

      byte[] inflated;
      if (entry.method() == METHOD_STORED) {
        inflated = compressed;
      } else {
        inflated = inflateDeflate(compressed, entry.name(), limits);
      }
      if (inflated.length > limits.maxEntryUncompressed()) {
        return ceilingBreach(
            "Entry '%s' uncompressed to %d bytes, exceeding the %d-byte per-entry limit."
                .formatted(entry.name(), inflated.length, limits.maxEntryUncompressed()));
      }
      totalUncompressed += inflated.length;
      if (totalUncompressed > limits.maxTotalUncompressed()) {
        return ceilingBreach(
            "Archive's total uncompressed size exceeds the %d-byte limit."
                .formatted(limits.maxTotalUncompressed()));
      }
      result.add(new ArchiveEntry(entry.name(), false, inflated.length, inflated));
    }
    return new ArchiveReadResult(List.of(), result);
  }

  private byte[] inflateDeflate(byte[] compressed, String entryName, Limits limits) {
    Inflater inflater = new Inflater(true);
    inflater.setInput(compressed);
    java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
    byte[] buffer = new byte[65536];
    try {
      while (!inflater.finished()) {
        int n = inflater.inflate(buffer);
        if (n == 0) {
          if (inflater.needsInput() || inflater.needsDictionary()) {
            break;
          }
        }
        out.write(buffer, 0, n);
        if (out.size() > limits.maxEntryUncompressed()) {
          throw new CeilingBreachException(
              "Entry '%s' exceeded the per-entry uncompressed size limit while inflating."
                  .formatted(entryName));
        }
        if (out.size() > RATIO_FREE_BYTES
            && compressed.length > 0
            && out.size() > (long) compressed.length * limits.maxRatio()) {
          throw new CeilingBreachException(
              "Entry '%s' exceeds the %d:1 compression ratio limit."
                  .formatted(entryName, limits.maxRatio()));
        }
      }
    } catch (DataFormatException e) {
      throw new ArchiveFormatException("Entry '" + entryName + "' is not a valid deflate stream.");
    } finally {
      inflater.end();
    }
    return out.toByteArray();
  }

  private ArchiveReadResult ceilingBreach(String message) {
    return new ArchiveReadResult(
        List.of(new CheckError("bundle.archive.expansion", message)), List.of());
  }

  private int localDataStart(ByteView view, long localHeaderOffset) {
    int lh = (int) localHeaderOffset;
    int nameLen = view.u16(lh + 26);
    int extraLen = view.u16(lh + 28);
    return lh + 30 + nameLen + extraLen;
  }

  /**
   * §2.2: "local headers are cross-checked" — name, compression method (and size, when the entry
   * does not use a streaming data descriptor, in which case the local header's size fields are
   * legitimately zero and not comparable).
   */
  private boolean crossCheckLocalHeader(
      ByteView view,
      long localHeaderOffset,
      String cdName,
      int cdMethod,
      long cdCompressedSize,
      int cdFlags) {
    int lh = (int) localHeaderOffset;
    if (lh + 30 > view.length() || view.u32(lh) != LFH_SIGNATURE) {
      return false;
    }
    int lhFlags = view.u16(lh + 6);
    int lhMethod = view.u16(lh + 8);
    long lhCompressedSize = view.u32(lh + 18);
    int lhNameLen = view.u16(lh + 26);
    byte[] lhNameBytes = view.slice(lh + 30, lhNameLen);
    String lhName = new String(lhNameBytes, StandardCharsets.UTF_8);
    if (!lhName.equals(cdName) || lhMethod != cdMethod) {
      return false;
    }
    boolean streaming =
        (cdFlags & GP_DATA_DESCRIPTOR_BIT) != 0 || (lhFlags & GP_DATA_DESCRIPTOR_BIT) != 0;
    return streaming || lhCompressedSize == cdCompressedSize;
  }

  private static boolean isPureAscii(byte[] bytes) {
    for (byte b : bytes) {
      if ((b & 0x80) != 0) {
        return false;
      }
    }
    return true;
  }

  /**
   * §2.2's full path-rule table, minus the ones already checked structurally (UTF-8, encryption,
   * compression method) or separately (duplicates, entry type).
   */
  private @Nullable String validateName(String name) {
    if (name.isEmpty()) {
      return "entry name is empty";
    }
    if (containsControlChar(name)) {
      return "contains a NUL or C0 control character";
    }
    if (name.startsWith("/") || name.matches("^[A-Za-z]:.*")) {
      return "is an absolute path or carries a drive prefix";
    }
    if (name.indexOf('\\') >= 0) {
      return "contains a backslash";
    }
    if (name.getBytes(StandardCharsets.UTF_8).length > MAX_NAME_BYTES) {
      return "exceeds " + MAX_NAME_BYTES + " bytes";
    }
    String withoutTrailingSlash = name.endsWith("/") ? name.substring(0, name.length() - 1) : name;
    if (withoutTrailingSlash.isEmpty()) {
      return "entry name is empty";
    }
    String[] segments = withoutTrailingSlash.split("/", -1);
    if (segments.length > MAX_DEPTH) {
      return "nesting depth exceeds " + MAX_DEPTH;
    }
    for (String segment : segments) {
      if (segment.isEmpty()) {
        return "contains an empty path segment";
      }
      if (segment.equals("..")) {
        return "contains a '..' path segment";
      }
      if (segment.equals(".")) {
        return "contains a '.' path segment";
      }
      if (!segment.equals(segment.strip())) {
        return "a path segment has leading or trailing whitespace";
      }
      if (segment.endsWith(".")) {
        return "a path segment has a trailing dot";
      }
      if (segment.getBytes(StandardCharsets.UTF_8).length > MAX_SEGMENT_BYTES) {
        return "a path segment exceeds " + MAX_SEGMENT_BYTES + " bytes";
      }
    }
    return null;
  }

  private static boolean containsControlChar(String name) {
    for (int i = 0; i < name.length(); i++) {
      char c = name.charAt(i);
      if (c == 0 || c < 0x20) {
        return true;
      }
    }
    return false;
  }

  private int findEndOfCentralDirectory(ByteView view) {
    int min = Math.max(0, view.length() - 22 - 65536);
    for (int i = view.length() - 22; i >= min; i--) {
      if (view.u32(i) == EOCD_SIGNATURE) {
        int commentLen = view.u16(i + 20);
        if (i + 22 + commentLen == view.length()) {
          return i;
        }
      }
    }
    throw new ArchiveFormatException("No end-of-central-directory record found.");
  }

  private record RawEntry(
      String name,
      boolean directory,
      int method,
      long compressedSize,
      long uncompressedSize,
      long localHeaderOffset,
      @Nullable Integer unixFileType) {}

  /** Little-endian, bounds-checked reads over the whole archive byte array. */
  private static final class ByteView {
    private final byte[] data;

    ByteView(byte[] data) {
      this.data = data;
    }

    int length() {
      return data.length;
    }

    int u16(int offset) {
      requireBounds(offset, 2);
      return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
    }

    long u32(int offset) {
      requireBounds(offset, 4);
      return (data[offset] & 0xFFL)
          | ((data[offset + 1] & 0xFFL) << 8)
          | ((data[offset + 2] & 0xFFL) << 16)
          | ((data[offset + 3] & 0xFFL) << 24);
    }

    byte[] slice(int offset, int len) {
      requireBounds(offset, len);
      byte[] out = new byte[len];
      System.arraycopy(data, offset, out, 0, len);
      return out;
    }

    private void requireBounds(int offset, int len) {
      if (offset < 0 || len < 0 || offset + len > data.length) {
        throw new ArchiveFormatException("Archive is truncated or corrupt.");
      }
    }
  }
}
