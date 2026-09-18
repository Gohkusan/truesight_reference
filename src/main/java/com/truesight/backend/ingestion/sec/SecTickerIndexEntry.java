package com.truesight.backend.ingestion.sec;

/** One row of the SEC's company_tickers.json index: a ticker, its CIK, and its name-of-record. */
public record SecTickerIndexEntry(String ticker, String cik10, String companyName) {
}
