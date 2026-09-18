package com.truesight.backend.domain;

/**
 * AC 2.2's four required states, exactly. Not an open-ended status string, because the
 * frontend needs to render each of these with a specific badge and tooltip, and a
 * five-way switch statement is a better contract than string-matching on free text.
 */
public enum CoverageStatus {
    /** Analysis completed and at least the base extraction succeeded. */
    COVERED,
    /** Queued or in progress. */
    PENDING,
    /** Attempted and did not complete; see {@code Holding.failureReason}. */
    FAILED,
    /**
     * SEC EDGAR has no filings for this company at all (distinct from FAILED: nothing
     * went wrong, there is simply nothing to analyse — typically a non-US company with
     * no US listing). AC 2.2 requires this explained in a tooltip, not silently merged
     * into FAILED.
     */
    NO_SEC_FILINGS
}
