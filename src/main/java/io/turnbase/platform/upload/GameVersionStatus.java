package io.turnbase.platform.upload;

/**
 * {@code data-model-catalog.md} §2's {@code game_version.status} check constraint, and {@code
 * bundle-format.md} §7.1's transition table — this pipeline is the writer for five of the seven
 * ({@code SUPERSEDED} and {@code DISCARDED}'s incoming edges belong to retirement, outside this
 * item's scope).
 */
enum GameVersionStatus {
  PENDING,
  PROCESSING,
  ACTIVE,
  FAILED,
  EXPIRED,
  SUPERSEDED,
  DISCARDED
}
