package io.turnbase.platform.upload;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/**
 * {@code file-uploads.md} step 1's request body. {@code version}'s format is stage A1's own
 * business rule ({@code version.format}), not a Bean Validation pattern — it has to land in the
 * {@code business-rule-violation} {@code errors} array alongside A2/A4, not the separate {@code
 * validation-failed} taxonomy entry. {@code @NotBlank}/{@code @Positive} here only guard against a
 * missing field, which is a different failure class (a malformed request body).
 */
public record CreateVersionRequest(@NotBlank String version, @Positive long bundleSize) {}
