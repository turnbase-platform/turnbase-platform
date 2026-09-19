package io.turnbase.platform.upload;

import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * {@code file-uploads.md} § Storage Abstraction: pre-signed URLs, put/get/delete, over whichever
 * backend (MinIO locally and in tests, AWS S3 in production) {@link ArtifactStoreConfig}'s raw
 * {@code S3Client} is pointed at via endpoint override. Object-key shapes are {@code
 * bundle-format.md} §5.1: {@code bundles/{gameId}/{version}/bundle.zip}, {@code
 * unpacked/{gameId}/{version}/<archive-relative path>}, {@code
 * artifacts/{gameId}/{version}/backend.jar}.
 */
interface ArtifactStore {

  /** A pre-signed {@code PUT} URL, valid until {@code expiresAt} — file-uploads.md step 1. */
  record PresignedUpload(URI url, Instant expiresAt) {}

  /**
   * {@code expiresAt} rather than a TTL: bundle-format.md §7.3 requires the URL's expiry and the
   * slot's {@code upload_expires_at} to be "the same instant, computed once" — the caller computes
   * it once and passes it here rather than each side deriving its own from a duration.
   */
  PresignedUpload presignPut(String key, Instant expiresAt);

  /** Empty when the object does not exist. */
  Optional<Long> sizeOf(String key);

  /**
   * {@code contentType} is whatever {@link #put}/{@link #putFile} recorded for this key — the
   * single source of truth for the fixed extension-to-content-type map is publication time ({@code
   * PublicationService}), not re-derived at serve time.
   */
  record StoredObject(byte[] data, String contentType) {}

  /** Empty when the object does not exist. */
  Optional<StoredObject> get(String key);

  /**
   * Downloads the object to a temp file for random-access reading (the ZIP central directory cannot
   * be read from a forward-only stream). Caller deletes the file when done.
   */
  Path downloadToTempFile(String key);

  void put(String key, byte[] content, String contentType);

  void putFile(String key, Path file, String contentType);

  void delete(String key);

  /** Best-effort, per §7.2 duty 4 — deletes every object under {@code prefix}. */
  void deletePrefix(String prefix);

  List<String> listKeys(String prefix);
}
