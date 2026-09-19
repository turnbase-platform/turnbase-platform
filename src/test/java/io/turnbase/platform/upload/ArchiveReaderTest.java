package io.turnbase.platform.upload;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;

/** {@code bundle-format.md} §2 — the single archive reader, Phase C's rules. */
class ArchiveReaderTest {

  private static final ArchiveReader.Limits GENEROUS_LIMITS =
      new ArchiveReader.Limits(1_000_000_000L, 256_000_000L, 100, 20_000);

  private final ArchiveReader reader = new ArchiveReader();

  @Test
  void validArchiveRoundTripsExactBytes() {
    byte[] zip =
        zipOf(
            Map.of(
                "manifest.json", "{}".getBytes(StandardCharsets.UTF_8),
                "backend/game.jar", new byte[] {1, 2, 3, 4, 5},
                "frontend/main.js", "console.log('hi');".getBytes(StandardCharsets.UTF_8)));

    ArchiveReadResult result = reader.read(zip, GENEROUS_LIMITS);

    assertThat(result.ok()).isTrue();
    assertThat(result.find("manifest.json")).isPresent();
    assertThat(result.find("manifest.json").get().data())
        .isEqualTo("{}".getBytes(StandardCharsets.UTF_8));
    assertThat(result.find("backend/game.jar").get().data()).isEqualTo(new byte[] {1, 2, 3, 4, 5});
  }

  @Test
  void storedEntriesRoundTripToo() {
    byte[] zip = zipOfStored(Map.of("manifest.json", "{\"a\":1}".getBytes(StandardCharsets.UTF_8)));

    ArchiveReadResult result = reader.read(zip, GENEROUS_LIMITS);

    assertThat(result.ok()).isTrue();
    assertThat(result.find("manifest.json").get().data())
        .isEqualTo("{\"a\":1}".getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void pathTraversalIsRejected() {
    byte[] zip = zipOf(Map.of("../etc/passwd", "x".getBytes(StandardCharsets.UTF_8)));

    ArchiveReadResult result = reader.read(zip, GENEROUS_LIMITS);

    assertThat(result.ok()).isFalse();
    assertThat(result.errors())
        .anySatisfy(e -> assertThat(e.check()).isEqualTo("bundle.archive.entryName"));
  }

  @Test
  void absolutePathIsRejected() {
    byte[] zip = zipOf(Map.of("/etc/passwd", "x".getBytes(StandardCharsets.UTF_8)));

    ArchiveReadResult result = reader.read(zip, GENEROUS_LIMITS);

    assertThat(result.ok()).isFalse();
    assertThat(result.errors())
        .anySatisfy(e -> assertThat(e.check()).isEqualTo("bundle.archive.entryName"));
  }

  @Test
  void backslashIsRejected() {
    byte[] zip = zipOf(Map.of("frontend\\main.js", "x".getBytes(StandardCharsets.UTF_8)));

    ArchiveReadResult result = reader.read(zip, GENEROUS_LIMITS);

    assertThat(result.ok()).isFalse();
    assertThat(result.errors())
        .anySatisfy(e -> assertThat(e.check()).isEqualTo("bundle.archive.entryName"));
  }

  @Test
  void emptySegmentIsRejected() {
    byte[] zip = zipOf(Map.of("frontend//main.js", "x".getBytes(StandardCharsets.UTF_8)));

    ArchiveReadResult result = reader.read(zip, GENEROUS_LIMITS);

    assertThat(result.ok()).isFalse();
    assertThat(result.errors())
        .anySatisfy(e -> assertThat(e.check()).isEqualTo("bundle.archive.entryName"));
  }

  @Test
  void trailingDotSegmentIsRejected() {
    byte[] zip = zipOf(Map.of("frontend./main.js", "x".getBytes(StandardCharsets.UTF_8)));

    ArchiveReadResult result = reader.read(zip, GENEROUS_LIMITS);

    assertThat(result.ok()).isFalse();
    assertThat(result.errors())
        .anySatisfy(e -> assertThat(e.check()).isEqualTo("bundle.archive.entryName"));
  }

  @Test
  void overLongSegmentIsRejected() {
    String longSegment = "a".repeat(300);
    byte[] zip = zipOf(Map.of(longSegment + ".js", "x".getBytes(StandardCharsets.UTF_8)));

    ArchiveReadResult result = reader.read(zip, GENEROUS_LIMITS);

    assertThat(result.ok()).isFalse();
    assertThat(result.errors())
        .anySatisfy(e -> assertThat(e.check()).isEqualTo("bundle.archive.entryName"));
  }

  @Test
  void caseInsensitiveDuplicatesAreRejected() {
    Map<String, byte[]> entries = new LinkedHashMap<>();
    entries.put("Manifest.json", "{}".getBytes(StandardCharsets.UTF_8));
    entries.put("manifest.json", "{}".getBytes(StandardCharsets.UTF_8));
    byte[] zip = zipOf(entries);

    ArchiveReadResult result = reader.read(zip, GENEROUS_LIMITS);

    assertThat(result.ok()).isFalse();
    assertThat(result.errors())
        .anySatisfy(e -> assertThat(e.check()).isEqualTo("bundle.archive.duplicate"));
  }

  @Test
  void entryCountOverTheLimitIsRejected() {
    Map<String, byte[]> entries = new LinkedHashMap<>();
    for (int i = 0; i < 5; i++) {
      entries.put("file" + i + ".txt", "x".getBytes(StandardCharsets.UTF_8));
    }
    byte[] zip = zipOf(entries);
    ArchiveReader.Limits tightLimits = new ArchiveReader.Limits(1_000_000, 1_000_000, 100, 3);

    ArchiveReadResult result = reader.read(zip, tightLimits);

    assertThat(result.ok()).isFalse();
    assertThat(result.errors())
        .anySatisfy(e -> assertThat(e.check()).isEqualTo("bundle.archive.format"));
  }

  @Test
  void perEntryUncompressedCeilingIsEnforced() {
    byte[] big = new byte[10_000];
    for (int i = 0; i < big.length; i++) {
      big[i] = (byte) (i % 251); // pseudo-random-ish to defeat deflate ratio false triggers
    }
    byte[] zip = zipOf(Map.of("big.bin", big));
    ArchiveReader.Limits tightLimits = new ArchiveReader.Limits(1_000_000, 5_000, 100, 20_000);

    ArchiveReadResult result = reader.read(zip, tightLimits);

    assertThat(result.ok()).isFalse();
    assertThat(result.errors())
        .anySatisfy(e -> assertThat(e.check()).isEqualTo("bundle.archive.expansion"));
  }

  @Test
  void totalUncompressedCeilingIsEnforced() {
    byte[] a = randomish(4000);
    byte[] b = randomish(4000);
    Map<String, byte[]> entries = new LinkedHashMap<>();
    entries.put("a.bin", a);
    entries.put("b.bin", b);
    byte[] zip = zipOf(entries);
    ArchiveReader.Limits tightLimits = new ArchiveReader.Limits(6000, 5000, 100, 20_000);

    ArchiveReadResult result = reader.read(zip, tightLimits);

    assertThat(result.ok()).isFalse();
    assertThat(result.errors())
        .anySatisfy(e -> assertThat(e.check()).isEqualTo("bundle.archive.expansion"));
  }

  @Test
  void compressionRatioBombIsRejected() {
    byte[] zeros = new byte[5_000_000]; // deflates to a tiny fraction of its size
    byte[] zip = zipOf(Map.of("bomb.bin", zeros));
    ArchiveReader.Limits limits =
        new ArchiveReader.Limits(1_000_000_000L, 256_000_000L, 100, 20_000);

    ArchiveReadResult result = reader.read(zip, limits);

    assertThat(result.ok()).isFalse();
    assertThat(result.errors())
        .anySatisfy(e -> assertThat(e.check()).isEqualTo("bundle.archive.expansion"));
  }

  @Test
  void corruptArchiveIsRejected() {
    byte[] garbage = "not a zip file at all".getBytes(StandardCharsets.UTF_8);

    ArchiveReadResult result = reader.read(garbage, GENEROUS_LIMITS);

    assertThat(result.ok()).isFalse();
    assertThat(result.errors())
        .anySatisfy(e -> assertThat(e.check()).isEqualTo("bundle.archive.format"));
  }

  @Test
  void directoryEntriesAreReadAsDirectories() {
    Map<String, byte[]> entries = new LinkedHashMap<>();
    entries.put("frontend/", new byte[0]);
    entries.put("frontend/main.js", "x".getBytes(StandardCharsets.UTF_8));
    byte[] zip = zipOf(entries);

    ArchiveReadResult result = reader.read(zip, GENEROUS_LIMITS);

    assertThat(result.ok()).isTrue();
    assertThat(result.entries()).anySatisfy(e -> assertThat(e.directory()).isTrue());
  }

  private static byte[] randomish(int size) {
    byte[] out = new byte[size];
    long seed = 12345;
    for (int i = 0; i < size; i++) {
      seed = seed * 6364136223846793005L + 1;
      out[i] = (byte) (seed >>> 33);
    }
    return out;
  }

  private static byte[] zipOf(Map<String, byte[]> entries) {
    try {
      ByteArrayOutputStream buffer = new ByteArrayOutputStream();
      try (ZipOutputStream zos = new ZipOutputStream(buffer, StandardCharsets.UTF_8)) {
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

  private static byte[] zipOfStored(Map<String, byte[]> entries) {
    try {
      ByteArrayOutputStream buffer = new ByteArrayOutputStream();
      try (ZipOutputStream zos = new ZipOutputStream(buffer, StandardCharsets.UTF_8)) {
        for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
          ZipEntry zipEntry = new ZipEntry(entry.getKey());
          zipEntry.setMethod(ZipEntry.STORED);
          zipEntry.setSize(entry.getValue().length);
          zipEntry.setCompressedSize(entry.getValue().length);
          CRC32 crc = new CRC32();
          crc.update(entry.getValue());
          zipEntry.setCrc(crc.getValue());
          zos.putNextEntry(zipEntry);
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
