package io.turnbase.platform.upload;

/**
 * One inflated archive entry — the shape {@code bundle-format.md} §2.4's single reader hands to
 * every consumer (the validator, the unpacker, and (one level deep) the backend JAR analyzer).
 * {@code data} is empty for a directory entry.
 */
record ArchiveEntry(String name, boolean directory, long uncompressedSize, byte[] data) {}
