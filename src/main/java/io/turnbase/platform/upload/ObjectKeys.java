package io.turnbase.platform.upload;

import java.util.UUID;

/**
 * {@code bundle-format.md} §5.1's three object-key shapes, derivable from {@code (gameId, version)}
 * alone — never from {@code version_id} — so both the upload pipeline and (later) the Game Host
 * fetch path can compute them without a database read.
 */
final class ObjectKeys {

  private ObjectKeys() {}

  static String bundle(UUID gameId, String version) {
    return "bundles/%s/%s/bundle.zip".formatted(gameId, version);
  }

  static String unpackedPrefix(UUID gameId, String version) {
    return "unpacked/%s/%s/".formatted(gameId, version);
  }

  static String unpacked(UUID gameId, String version, String archiveRelativePath) {
    return unpackedPrefix(gameId, version) + archiveRelativePath;
  }

  static String artifactsPrefix(UUID gameId, String version) {
    return "artifacts/%s/%s/".formatted(gameId, version);
  }

  static String backendJar(UUID gameId, String version) {
    return artifactsPrefix(gameId, version) + "backend.jar";
  }
}
