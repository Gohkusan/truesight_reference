package com.truesight.backend.ingestion.csv;

import com.truesight.backend.common.ValidationException;
import com.truesight.backend.ingestion.csv.CsvColumnAliases.Column;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * AC 2.1's parser: alias resolution, validation, duplicate merging, non-equity
 * filtering — everything that can be decided WITHOUT calling out to SEC. (Unrecognised-
 * ticker detection needs the SEC ticker index and happens one layer up, in
 * PortfolioIngestionService — see CsvParseResult's Javadoc.)
 */
@Component
public class PortfolioCsvParser {

    private static final Set<String> NON_EQUITY_ASSET_CLASSES = Set.of(
            "CASH", "BOND", "BONDS", "FIXEDINCOME", "FUND", "ETF", "MONEYMARKET");

    // Recognises a currency symbol/comma-grouped number, optionally suffixed with a
    // magnitude letter (B/M/K) — e.g. "$1,450,000", "1.45B", "290M". AC 2.1 doesn't
    // explicitly require magnitude-suffix parsing, but the old prototype doc's "Dynamic
    // AUM & Financial Exposure Modeling" notes it as a real institutional-export
    // pattern worth keeping, and it costs little to support for MarketValue specifically.
    private static final Pattern NUMERIC_CLEANUP = Pattern.compile("[$,\\s]");
    private static final Pattern MAGNITUDE_SUFFIX = Pattern.compile("(?i)^(-?[0-9.]+)\\s*([BMK])$");

    public CsvParseResult parse(String csvContent) {
        List<List<String>> rawRows = SimpleCsvTokenizer.tokenize(csvContent)
                .stream()
                // AC-adjacent convenience from the old prototype doc: "#"-prefixed
                // comment lines and fully blank lines are skipped, not treated as data.
                .filter(row -> !(row.size() == 1 && (row.get(0).isBlank() || row.get(0).trim().startsWith("#"))))
                .toList();

        if (rawRows.isEmpty()) {
            throw new ValidationException("The file has no data rows");
        }

        List<String> headerRow = rawRows.get(0);
        Map<Column, Integer> columnIndex = resolveHeaderColumns(headerRow);

        if (!columnIndex.containsKey(Column.TICKER)) {
            throw new ValidationException(
                    "Missing a ticker column. Expected one of: Ticker, Symbol, Identifier, Code");
        }

        List<List<String>> dataRows = rawRows.subList(1, rawRows.size());
        if (dataRows.isEmpty()) {
            throw new ValidationException("The file has a header row but zero data rows");
        }

        List<ParsedCsvRow> parsedRows = new ArrayList<>();
        List<CsvParseResult.SkippedRow> skipped = new ArrayList<>();

        int rowNumber = 1; // 1-indexed, counting the header as row 0 for user-facing messages
        for (List<String> raw : dataRows) {
            rowNumber++;
            String ticker = cell(raw, columnIndex, Column.TICKER);
            if (ticker == null || ticker.isBlank()) {
                continue; // a row with no ticker at all contributes nothing parseable; silently skipped, not an error
            }
            ticker = ticker.trim().toUpperCase();

            String assetClass = cell(raw, columnIndex, Column.ASSET_CLASS);
            if (assetClass != null && NON_EQUITY_ASSET_CLASSES.contains(assetClass.trim().toUpperCase())) {
                // AC 2.1: "Rows whose asset class is not equity... are shown and
                // skipped with a note" — recorded, never silently dropped.
                skipped.add(new CsvParseResult.SkippedRow(rowNumber, ticker,
                        "Asset class \"" + assetClass.trim() + "\" is not equity"));
                continue;
            }

            String name = cell(raw, columnIndex, Column.NAME);
            BigDecimal weight = parseNullableDecimal(cell(raw, columnIndex, Column.WEIGHT_PERCENT));
            BigDecimal shares = parseNullableDecimal(cell(raw, columnIndex, Column.SHARES));
            BigDecimal marketValue = parseNullableDecimal(cell(raw, columnIndex, Column.MARKET_VALUE));
            String currency = cell(raw, columnIndex, Column.CURRENCY);

            parsedRows.add(new ParsedCsvRow(rowNumber, ticker, name, assetClass, weight, shares, marketValue, currency));
        }

        List<CsvParseResult.DuplicateWarning> duplicateWarnings = new ArrayList<>();
        List<ParsedCsvRow> merged = mergeDuplicateTickers(parsedRows, duplicateWarnings);

        return new CsvParseResult(merged, duplicateWarnings, skipped);
    }

    private Map<Column, Integer> resolveHeaderColumns(List<String> headerRow) {
        Map<Column, Integer> index = new LinkedHashMap<>();
        for (int i = 0; i < headerRow.size(); i++) {
            String normalized = CsvColumnAliases.normalizeHeader(headerRow.get(i));
            Column column = CsvColumnAliases.resolve(normalized);
            // First occurrence wins if a file has two columns that alias to the same
            // logical column (unusual, but "first wins" is at least deterministic
            // rather than silently picking whichever the map iterates last).
            if (column != null) {
                index.putIfAbsent(column, i);
            }
        }
        return index;
    }

    private String cell(List<String> row, Map<Column, Integer> columnIndex, Column column) {
        Integer idx = columnIndex.get(column);
        if (idx == null || idx >= row.size()) {
            return null;
        }
        String value = row.get(idx);
        return (value == null || value.isBlank()) ? null : value.trim();
    }

    /**
     * AC 2.1: "Missing weights and shares are shown blank. The system never fills in
     * default values." A blank/unparseable cell returns null here — NOT
     * BigDecimal.ZERO, which would be indistinguishable from "explicitly reported as
     * zero" and is exactly the fabricated-default the acceptance criterion forbids.
     */
    private BigDecimal parseNullableDecimal(String raw) {
        if (raw == null) {
            return null;
        }
        var magnitudeMatch = MAGNITUDE_SUFFIX.matcher(raw.trim());
        if (magnitudeMatch.matches()) {
            BigDecimal base = new BigDecimal(magnitudeMatch.group(1));
            BigDecimal multiplier = switch (magnitudeMatch.group(2).toUpperCase()) {
                case "B" -> BigDecimal.valueOf(1_000_000_000L);
                case "M" -> BigDecimal.valueOf(1_000_000L);
                case "K" -> BigDecimal.valueOf(1_000L);
                default -> BigDecimal.ONE;
            };
            return base.multiply(multiplier);
        }

        String cleaned = NUMERIC_CLEANUP.matcher(raw).replaceAll("");
        boolean isPercentString = cleaned.endsWith("%");
        if (isPercentString) {
            cleaned = cleaned.substring(0, cleaned.length() - 1);
        }
        if (cleaned.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(cleaned);
        } catch (NumberFormatException e) {
            return null; // an unparseable value is treated as "not provided", never as zero
        }
    }

    /**
     * AC 2.1: "Duplicate tickers are merged into one row with a visible warning."
     * Merge rule: keep the first row's identifying fields, SUM numeric fields across
     * duplicates (two rows for the same ticker most plausibly represent a position
     * held in two lots/accounts that the user wants treated as one holding — summing
     * weight and market value is the only merge rule that keeps "total portfolio
     * weight" meaningful afterward).
     */
    private List<ParsedCsvRow> mergeDuplicateTickers(List<ParsedCsvRow> rows, List<CsvParseResult.DuplicateWarning> warningsOut) {
        Map<String, List<ParsedCsvRow>> byTicker = new LinkedHashMap<>();
        for (ParsedCsvRow row : rows) {
            byTicker.computeIfAbsent(row.ticker(), k -> new ArrayList<>()).add(row);
        }

        List<ParsedCsvRow> result = new ArrayList<>();
        for (Map.Entry<String, List<ParsedCsvRow>> entry : byTicker.entrySet()) {
            List<ParsedCsvRow> group = entry.getValue();
            if (group.size() == 1) {
                result.add(group.get(0));
                continue;
            }
            warningsOut.add(new CsvParseResult.DuplicateWarning(
                    entry.getKey(), group.stream().map(ParsedCsvRow::rowNumber).toList()));
            result.add(mergeGroup(group));
        }
        return result;
    }

    private ParsedCsvRow mergeGroup(List<ParsedCsvRow> group) {
        ParsedCsvRow first = group.get(0);
        BigDecimal weight = sumNullable(group.stream().map(ParsedCsvRow::weightPercent).toList());
        BigDecimal shares = sumNullable(group.stream().map(ParsedCsvRow::shares).toList());
        BigDecimal marketValue = sumNullable(group.stream().map(ParsedCsvRow::marketValue).toList());
        return new ParsedCsvRow(first.rowNumber(), first.ticker(), first.companyName(),
                first.assetClass(), weight, shares, marketValue, first.currency());
    }

    /** Sums a list where every element may individually be null; returns null only if ALL are null. */
    private BigDecimal sumNullable(List<BigDecimal> values) {
        BigDecimal sum = null;
        for (BigDecimal v : values) {
            if (v == null) {
                continue;
            }
            sum = (sum == null) ? v : sum.add(v);
        }
        return sum;
    }
}
