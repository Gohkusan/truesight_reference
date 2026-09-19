package com.truesight.backend.web;

import com.truesight.backend.common.NotFoundException;
import com.truesight.backend.domain.Company;
import com.truesight.backend.ingestion.sec.SecTickerIndexService;
import com.truesight.backend.repository.CompanyRepository;
import com.truesight.backend.web.dto.TickerSearchResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * AC 2.3: "Search-as-you-type against the SEC ticker index shows ticker and company
 * name." Read-only, not scoped to a portfolio or user — the SEC ticker index is public
 * reference data, the same for every account, so this sits outside the
 * /api/portfolios/{id}/... ownership-scoped tree entirely.
 *
 * <p>The sector override (AC 4.9) is also here because Company is shared data: a
 * user's override is visible to every user who sees that company. That is a
 * deliberate simplification for a reference build — per-user sector overrides would
 * need a join table like RelationshipReview — and is recorded on the company as
 * sectorUserOverridden so the LLM never overwrites it again.
 */
@RestController
@RequestMapping("/api/companies")
@Tag(name = "Companies", description = "SEC ticker index search (AC 2.3) and sector override (AC 4.9).")
public class CompanySearchController {

    private final SecTickerIndexService tickerIndexService;
    private final CompanyRepository companyRepository;

    public CompanySearchController(SecTickerIndexService tickerIndexService, CompanyRepository companyRepository) {
        this.tickerIndexService = tickerIndexService;
        this.companyRepository = companyRepository;
    }

    @GetMapping("/search")
    @Operation(summary = "Search the SEC ticker index by ticker prefix or name substring")
    public List<TickerSearchResult> search(@RequestParam("q") String query) {
        return tickerIndexService.searchByPrefix(query, 20).stream()
                .map(TickerSearchResult::from)
                .toList();
    }

    @PutMapping("/{companyId}/sector")
    @Operation(summary = "Override a company's sector (AC 4.9). Empty string clears the override.")
    @Transactional
    public Map<String, Object> setSector(@PathVariable Long companyId, @RequestBody Map<String, String> body) {
        Company c = companyRepository.findById(companyId).orElseThrow(() -> new NotFoundException("Company not found"));
        String sector = body.getOrDefault("sector", "").trim();
        if (sector.isEmpty()) {
            c.setSector(null);
            c.setSectorUserOverridden(false);
        } else {
            c.setSector(sector);
            c.setSectorUserOverridden(true);
        }
        c.touch();
        return Map.of("id", c.getId(), "sector", c.getSector() == null ? "" : c.getSector(), "userOverridden", c.isSectorUserOverridden());
    }
}
