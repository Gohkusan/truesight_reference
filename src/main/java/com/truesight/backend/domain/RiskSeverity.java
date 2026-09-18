package com.truesight.backend.domain;

/**
 * AC 3.1 and AC 6.1's severity bands. UNKNOWN is a first-class member, not the absence
 * of a value: "A holding with no data shows Unknown, never Low" is a direct acceptance
 * criterion, so the enum itself must be able to represent that state rather than
 * relying on every call site to remember to check for null separately.
 */
public enum RiskSeverity {
    UNKNOWN,
    LOW,
    MODERATE,
    ELEVATED,
    CRITICAL
}
