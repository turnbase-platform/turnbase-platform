package io.turnbase.platform.common;

/**
 * One entry in a {@code validation-failed} response's {@code errors} array ({@code
 * error-handling.md} § Response Shape). Never carries the rejected value — {@code
 * input-validation.md} § Rejection model: reflecting attacker input back through an error path is
 * itself a log-injection or content-reflection vector, and the client already knows what it sent.
 */
public record FieldValidationError(String field, String message) {}
