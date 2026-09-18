package com.truesight.backend.web.dto;

import com.truesight.backend.ingestion.sec.SecTickerIndexEntry;

public record TickerSearchResult(String ticker, String companyName) {
    public static TickerSearchResult from(SecTickerIndexEntry entry) {
        return new TickerSearchResult(entry.ticker(), entry.companyName());
    }
}
