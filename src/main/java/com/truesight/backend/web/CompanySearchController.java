package com.truesight.backend.web;

import com.truesight.backend.ingestion.sec.SecTickerIndexService;
import com.truesight.backend.web.dto.TickerSearchResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * AC 2.3: "Search-as-you-type against the SEC ticker index shows ticker and company
 * name." Read-only, not scoped to a portfolio or user — the SEC ticker index is public
 * reference data, the same for every account, so this sits outside the
 * /api/portfolios/{id}/... ownership-scoped tree entirely.
 */
@RestController
@RequestMapping("/api/companies")
@Tag(name = "Companies", description = "SEC ticker index search (AC 2.3).")
public class CompanySearchController {

    private final SecTickerIndexService tickerIndexService;

    public CompanySearchController(SecTickerIndexService tickerIndexService) {
        this.tickerIndexService = tickerIndexService;
    }

    @GetMapping("/search")
    @Operation(summary = "Search the SEC ticker index by ticker prefix or name substring")
    public List<TickerSearchResult> search(@RequestParam("q") String query) {
        return tickerIndexService.searchByPrefix(query, 20).stream()
                .map(TickerSearchResult::from)
                .toList();
    }
}
