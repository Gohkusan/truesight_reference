package com.truesight.backend.ingestion.sec;

import java.time.LocalDate;

/**
 * One filing's metadata, as listed in a company's SEC submissions feed — before the
 * actual document text has been fetched. accessionNumber is the key AC 8.1's
 * change-detection is keyed on: "filings are keyed by SEC accession number... only
 * changed inputs are re-analysed".
 */
public record SecFilingSummary(
        String accessionNumber,
        String formType,
        LocalDate filingDate,
        String primaryDocument
) {
}
