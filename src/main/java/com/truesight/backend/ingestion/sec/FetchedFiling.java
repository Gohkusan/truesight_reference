package com.truesight.backend.ingestion.sec;

import com.truesight.backend.domain.SourceType;
import java.time.LocalDate;

/**
 * A filing's full, fetched text plus its identifying metadata — this is what the
 * verbatim excerpt guard (ExcerptVerificationService) searches against. Every field
 * here is exactly what one row of Evidence needs, since a passing excerpt check
 * produces an Evidence row keyed on this filing's own accessionNumber/sourceUrl/date.
 */
public record FetchedFiling(
        String accessionNumber,
        SourceType sourceType,
        LocalDate filingDate,
        String documentUrl,
        String fullText
) {
}
