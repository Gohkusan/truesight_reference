package com.truesight.backend.ingestion.csv;

import java.util.List;
import java.util.Map;

/**
 * AC 2.1's exact alias list: "Ticker/Symbol, Name/CompanyName, Weight/WeightPercent,
 * Shares/Quantity, MarketValue/Value". Extended slightly with the old prototype doc's
 * broader institutional-format aliases (ISIN, AssetClass/Class/Type,
 * Sector/Industry/GICS, Currency/CCY) since those are genuinely useful and don't
 * conflict with anything the spec restricts — the spec sets a floor, not a ceiling, on
 * alias coverage.
 *
 * <p>Matching is case-insensitive and ignores internal spaces/underscores (so "Market
 * Value", "MarketValue", and "market_value" are all the same alias) — CSV exports from
 * different portfolio systems format headers inconsistently, and AC 2.1 explicitly
 * requires "accepts columns in any order" with alias recognition, which only works in
 * practice if the matching is this forgiving.
 */
public final class CsvColumnAliases {

    private CsvColumnAliases() {
    }

    public enum Column {
        TICKER, ISIN, NAME, ASSET_CLASS, SECTOR, WEIGHT_PERCENT, SHARES, MARKET_VALUE, CURRENCY
    }

    private static final Map<Column, List<String>> ALIASES = Map.of(
            Column.TICKER, List.of("TICKER", "SYMBOL", "IDENTIFIER", "CODE"),
            Column.ISIN, List.of("ISIN"),
            Column.NAME, List.of("NAME", "COMPANYNAME", "SECURITYNAME", "DESCRIPTION"),
            Column.ASSET_CLASS, List.of("ASSETCLASS", "CLASS", "TYPE"),
            Column.SECTOR, List.of("SECTOR", "INDUSTRY", "GICS"),
            Column.WEIGHT_PERCENT, List.of("WEIGHTPERCENT", "WEIGHT", "PCT", "PORTFOLIOWEIGHT"),
            Column.SHARES, List.of("SHARES", "QUANTITY", "UNITS", "QTY"),
            Column.MARKET_VALUE, List.of("MARKETVALUEUSD", "MARKETVALUE", "MV", "VALUE"),
            Column.CURRENCY, List.of("CURRENCY", "CCY")
    );

    /** Normalises a raw CSV header cell for alias comparison: uppercase, strip spaces/underscores. */
    public static String normalizeHeader(String rawHeader) {
        if (rawHeader == null) {
            return "";
        }
        return rawHeader.trim().toUpperCase().replaceAll("[ _]", "");
    }

    /** Which logical Column a normalised header cell corresponds to, or null if unrecognised. */
    public static Column resolve(String normalizedHeader) {
        for (Map.Entry<Column, List<String>> entry : ALIASES.entrySet()) {
            if (entry.getValue().contains(normalizedHeader)) {
                return entry.getKey();
            }
        }
        return null;
    }
}
