package com.truesight.backend.web.dto;

import com.truesight.backend.ingestion.PortfolioIngestionService;
import com.truesight.backend.ingestion.PortfolioIngestionService.PreviewResult;
import java.util.List;

public record CsvPreviewResponse(
        List<PortfolioIngestionService.PreviewRow> rows,
        List<com.truesight.backend.ingestion.csv.CsvParseResult.DuplicateWarning> duplicateWarnings,
        List<com.truesight.backend.ingestion.csv.CsvParseResult.SkippedRow> skippedNonEquityRows,
        List<String> unrecognisedTickers,
        // Not persisted anywhere — the browser must send this exact CSV content back
        // on confirm. Simpler than a server-side "pending upload" store for a
        // reference implementation, at the cost of re-sending the file bytes on
        // confirm; noted as the trade-off it is.
        String csvContentToEchoBackOnConfirm
) {
    public static CsvPreviewResponse from(PreviewResult result, String originalCsvContent) {
        return new CsvPreviewResponse(
                result.rows(), result.duplicateWarnings(), result.skippedNonEquityRows(),
                result.unrecognisedTickers(), originalCsvContent
        );
    }
}
