package com.truesight.backend.ingestion.csv;

import java.math.BigDecimal;

/**
 * One row of a CSV upload after column-alias resolution and value parsing, but BEFORE
 * ticker resolution against SEC and before persistence. This is the intermediate shape
 * the preview screen (AC 2.1: "shows a preview table of parsed rows; user confirms
 * before analysis starts") is built from — parsing and persisting are deliberately two
 * separate steps so the user sees exactly what was understood from their file before
 * anything is written to the database or any SEC/LLM call is made.
 *
 * <p>weightPercent and shares are nullable BigDecimal, not primitives defaulted to
 * zero — AC 2.1: "Missing weights and shares are shown blank. The system never fills
 * in default values." A null here must stay null all the way to the Holding entity.
 */
public record ParsedCsvRow(
        int rowNumber,
        String ticker,
        String companyName,
        String assetClass,
        BigDecimal weightPercent,
        BigDecimal shares,
        BigDecimal marketValue,
        String currency
) {
}
