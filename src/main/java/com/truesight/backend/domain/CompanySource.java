package com.truesight.backend.domain;

/** How a Company row first entered the system. Provenance, not a live status. */
public enum CompanySource {
    /** Matched against the SEC company_tickers.json index at ingestion time. */
    SEC_INDEX,
    /** Discovered only as a name inside a filing's text (a supplier/customer mention). */
    LLM_EXTRACTED,
    /** Entered directly by the user (e.g. "add a holding by ticker", AC 2.3). */
    USER_ADDED
}
