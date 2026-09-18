package com.truesight.backend.domain;

/**
 * AC 7.3's fixed set: "Dismiss asks for a reason (not relevant, duplicate, wrong
 * company)... reasons are stored for later tuning." A closed enum rather than free text
 * because "later tuning" implies aggregating dismissal reasons across many alerts to
 * find patterns (e.g. "the relevance threshold is letting through too many duplicates")
 * — that only works if the reasons are a small fixed vocabulary, not arbitrary strings.
 */
public enum DismissReason {
    NOT_RELEVANT,
    DUPLICATE,
    WRONG_COMPANY
}
