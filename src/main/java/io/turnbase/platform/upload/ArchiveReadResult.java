package io.turnbase.platform.upload;

import io.turnbase.platform.common.CheckError;
import java.util.List;
import java.util.Optional;

/**
 * The outcome of one {@link ArchiveReader#read} call. {@code errors} non-empty means Phase C failed
 * (a barrier — {@code bundle-format.md} §3.3): {@code entries} is then empty or partial and must
 * not be used. Every accumulated C1-C4 error is collected before this result is returned; only a C5
 * ceiling breach short-circuits with a single error, per §2.3's "aborts immediately".
 */
record ArchiveReadResult(List<CheckError> errors, List<ArchiveEntry> entries) {

  boolean ok() {
    return errors.isEmpty();
  }

  Optional<ArchiveEntry> find(String name) {
    return entries.stream().filter(e -> !e.directory() && e.name().equals(name)).findFirst();
  }
}
