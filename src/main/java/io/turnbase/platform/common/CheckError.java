package io.turnbase.platform.common;

/**
 * One failed rule in a {@code business-rule-violation} response's {@code errors} array ( {@code
 * error-handling.md} § Bundle Validation Errors) — the same field-level shape as a validation
 * error, with {@code field} replaced by {@code check} to name the rule rather than the input.
 */
public record CheckError(String check, String message) {}
