package io.turnbase.platform.upload;

import io.turnbase.platform.common.CheckError;
import java.util.List;

/**
 * Layer 1 (JSON Schema) and Layer 2 (supplementary) findings from validating a single manifest
 * document ({@code manifest-schema.md} § Validation). {@code errors} are fatal — any one of them
 * fails the bundle (D2, {@code bundle-format.md} §3.2). {@code warnings} are unrecognized keys and
 * never cause rejection on their own.
 */
public record ManifestValidationResult(List<CheckError> errors, List<CheckError> warnings) {

  public boolean valid() {
    return errors.isEmpty();
  }
}
