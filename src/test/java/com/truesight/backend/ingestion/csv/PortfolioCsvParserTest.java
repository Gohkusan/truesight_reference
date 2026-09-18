package com.truesight.backend.ingestion.csv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.truesight.backend.common.ValidationException;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** Covers AC 2.1's specific, individually testable requirements, one test per clause. */
class PortfolioCsvParserTest {

    private final PortfolioCsvParser parser = new PortfolioCsvParser();

    @Test
    void acceptsColumnsInAnyOrderWithAliases() {
        // Weight/Shares/Value columns in a deliberately scrambled order, using
        // alias names rather than the "canonical" ones, per AC 2.1.
        String csv = """
                Symbol,MarketValue,CompanyName,Qty
                AAPL,1000000,Apple Inc,5000
                """;
        CsvParseResult result = parser.parse(csv);
        assertThat(result.rows()).hasSize(1);
        ParsedCsvRow row = result.rows().get(0);
        assertThat(row.ticker()).isEqualTo("AAPL");
        assertThat(row.companyName()).isEqualTo("Apple Inc");
        assertThat(row.marketValue()).isEqualByComparingTo("1000000");
        assertThat(row.shares()).isEqualByComparingTo("5000");
    }

    @Test
    void rejectsFileWithMissingTickerColumn() {
        String csv = "Name,Weight\nApple,10\n";
        assertThatThrownBy(() -> parser.parse(csv))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("ticker column");
    }

    @Test
    void rejectsFileWithZeroDataRows() {
        String csv = "Ticker,Name\n";
        assertThatThrownBy(() -> parser.parse(csv))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("zero data rows");
    }

    @Test
    void duplicateTickersAreMergedWithAVisibleWarning() {
        String csv = """
                Ticker,Weight,MarketValue
                AAPL,5,100
                AAPL,3,60
                MSFT,10,200
                """;
        CsvParseResult result = parser.parse(csv);

        assertThat(result.rows()).hasSize(2); // AAPL merged into one, MSFT untouched
        ParsedCsvRow merged = result.rows().stream().filter(r -> r.ticker().equals("AAPL")).findFirst().orElseThrow();
        assertThat(merged.weightPercent()).isEqualByComparingTo("8"); // 5 + 3
        assertThat(merged.marketValue()).isEqualByComparingTo("160"); // 100 + 60

        assertThat(result.duplicateWarnings()).hasSize(1);
        assertThat(result.duplicateWarnings().get(0).ticker()).isEqualTo("AAPL");
        assertThat(result.duplicateWarnings().get(0).rowNumbers()).hasSize(2);
    }

    @Test
    void missingWeightsAndSharesAreNullNeverDefaulted() {
        // AC 2.1: "The system never fills in default values." A row with no weight or
        // shares column value at all must carry null through, not zero.
        String csv = """
                Ticker,Name
                AAPL,Apple Inc
                """;
        CsvParseResult result = parser.parse(csv);
        ParsedCsvRow row = result.rows().get(0);
        assertThat(row.weightPercent()).isNull();
        assertThat(row.shares()).isNull();
    }

    @Test
    void blankWeightCellIsNullNotZero() {
        String csv = "Ticker,Weight\nAAPL,\nMSFT,5\n";
        CsvParseResult result = parser.parse(csv);
        ParsedCsvRow aapl = result.rows().stream().filter(r -> r.ticker().equals("AAPL")).findFirst().orElseThrow();
        assertThat(aapl.weightPercent()).isNull();
    }

    @Test
    void nonEquityAssetClassRowsAreSkippedWithANote() {
        String csv = """
                Ticker,AssetClass,Weight
                AAPL,Equity,10
                CASH1,Cash,5
                BOND1,Bond,15
                """;
        CsvParseResult result = parser.parse(csv);

        assertThat(result.rows()).extracting(ParsedCsvRow::ticker).containsExactly("AAPL");
        assertThat(result.skippedNonEquityRows()).hasSize(2);
        assertThat(result.skippedNonEquityRows())
                .extracting(CsvParseResult.SkippedRow::ticker)
                .containsExactlyInAnyOrder("CASH1", "BOND1");
        assertThat(result.skippedNonEquityRows().get(0).reason()).isNotBlank();
    }

    @Test
    void handlesQuotedFieldsWithEmbeddedCommas() {
        String csv = "Ticker,Name\nBRK,\"Berkshire Hathaway, Inc.\"\n";
        CsvParseResult result = parser.parse(csv);
        assertThat(result.rows().get(0).companyName()).isEqualTo("Berkshire Hathaway, Inc.");
    }

    @Test
    void ignoresCommentAndBlankLines() {
        String csv = """
                # This is a comment line
                Ticker,Weight

                AAPL,10
                """;
        CsvParseResult result = parser.parse(csv);
        assertThat(result.rows()).hasSize(1);
    }

    @Test
    void stripsUtf8ByteOrderMark() {
        String csv = "﻿Ticker,Weight\nAAPL,10\n";
        CsvParseResult result = parser.parse(csv);
        assertThat(result.rows()).hasSize(1);
        assertThat(result.rows().get(0).ticker()).isEqualTo("AAPL");
    }

    @Test
    void parsesDollarSignsAndCommaGroupedMarketValues() {
        String csv = "Ticker,MarketValue\nAAPL,\"$1,450,000\"\n";
        CsvParseResult result = parser.parse(csv);
        assertThat(result.rows().get(0).marketValue()).isEqualByComparingTo("1450000");
    }

    @Test
    void parsesMagnitudeSuffixedMarketValues() {
        String csv = "Ticker,MarketValue\nAAPL,1.45B\nMSFT,290M\n";
        CsvParseResult result = parser.parse(csv);
        ParsedCsvRow aapl = result.rows().stream().filter(r -> r.ticker().equals("AAPL")).findFirst().orElseThrow();
        assertThat(aapl.marketValue()).isEqualByComparingTo(new BigDecimal("1450000000"));
    }

    @Test
    void rowsWithBlankTickerAreSkippedSilently() {
        // Not an "error" row and not a "skipped with note" row (that bucket is
        // specifically for non-equity) — a row with literally no ticker has nothing
        // to key a holding on, so it simply contributes nothing.
        String csv = "Ticker,Name\n,Some Row With No Ticker\nAAPL,Apple\n";
        CsvParseResult result = parser.parse(csv);
        assertThat(result.rows()).hasSize(1);
        assertThat(result.rows().get(0).ticker()).isEqualTo("AAPL");
    }
}
