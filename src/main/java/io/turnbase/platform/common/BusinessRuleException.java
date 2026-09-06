package io.turnbase.platform.common;

import java.util.List;

/**
 * {@code error-handling.md} taxonomy: {@code business-rule-violation}, 422 — a domain rule failure
 * (e.g. a game version not strictly higher than the current one). {@code checks} is optional:
 * present for the synchronous, "all failures in one response" case ({@code error-handling.md} §
 * Bundle Validation Errors § Synchronous), empty when the violation has no per-check breakdown.
 */
public class BusinessRuleException extends DomainException {

  private final List<CheckError> checks;

  public BusinessRuleException(String message) {
    this(message, List.of());
  }

  public BusinessRuleException(String message, List<CheckError> checks) {
    super(ProblemType.BUSINESS_RULE_VIOLATION, message);
    this.checks = List.copyOf(checks);
  }

  public List<CheckError> checks() {
    return checks;
  }
}
