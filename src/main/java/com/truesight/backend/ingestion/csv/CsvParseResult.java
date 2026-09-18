package com.truesight.backend.ingestion.csv;

import java.util.List;

/**
 * The full outcome of parsing an uploaded CSV, before any SEC lookup. Three buckets,
 * matching AC 2.1's three distinct row outcomes exactly — the preview screen renders
 * each one differently, so they're kept as three separate lists rather than one list
 * with a status flag, to make "how many of each" trivial for the frontend to compute
 * and impossible to accidentally conflate.
 *
 * <p>duplicateWarnings and skippedNonEquityRows are populated during parsing itself
 * (duplicate ticker detection and asset-class filtering need no SEC/network access).
 * Unrecognised-ticker detection happens one step later, in
 * {@code PortfolioIngestionService}, because it requires the SEC ticker index — see
 * that class for why the two steps are split.
 */
public record CsvParseResult(
        List<ParsedCsvRow> rows,
        List<DuplicateWarning> duplicateWarnings,
        List<SkippedRow> skippedNonEquityRows
) {

    public record DuplicateWarning(String ticker, List<Integer> rowNumbers) {
    }

    public record SkippedRow(int rowNumber, String ticker, String reason) {
    }
}
