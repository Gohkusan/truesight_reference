package com.truesight.backend.web;

import com.truesight.backend.domain.RiskSeverity;
import com.truesight.backend.risk.RiskQueryService;
import com.truesight.backend.risk.RiskScoringService;
import com.truesight.backend.security.CurrentUser;
import com.truesight.backend.service.PortfolioService;
import com.truesight.backend.web.dto.RiskResponses.Concentration;
import com.truesight.backend.web.dto.RiskResponses.RiskItem;
import com.truesight.backend.web.dto.RiskResponses.Summary;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Epic 6. All endpoints 404 for a portfolio the caller does not own. */
@RestController
@RequestMapping("/api/portfolios/{portfolioId}/risks")
@Tag(name = "Risks", description = "Risk scores, factors, trends and concentration (Epic 6, AC 4.9).")
public class RiskController {

    private final PortfolioService portfolioService;
    private final RiskQueryService riskQueryService;
    private final RiskScoringService riskScoringService;
    private final CurrentUser currentUser;

    public RiskController(PortfolioService portfolioService, RiskQueryService riskQueryService,
                          RiskScoringService riskScoringService, CurrentUser currentUser) {
        this.portfolioService = portfolioService;
        this.riskQueryService = riskQueryService;
        this.riskScoringService = riskScoringService;
        this.currentUser = currentUser;
    }

    @GetMapping("/summary")
    @Operation(summary = "Composite risk score with band and coverage denominator (AC 3.1, 6.1)")
    public Summary summary(@PathVariable Long portfolioId) {
        portfolioService.getOwned(portfolioId, currentUser.id());
        return riskQueryService.summary(portfolioId, currentUser.id());
    }

    @GetMapping
    @Operation(summary = "Ranked risk list", description = "Most severe first, Unknown last. Filter by minSeverity and/or holdingId (AC 6.3).")
    public List<RiskItem> list(@PathVariable Long portfolioId,
                               @RequestParam(required = false) RiskSeverity minSeverity,
                               @RequestParam(required = false) Long holdingId) {
        portfolioService.getOwned(portfolioId, currentUser.id());
        return riskQueryService.list(portfolioId, currentUser.id(), minSeverity, holdingId);
    }

    @GetMapping("/concentration")
    @Operation(summary = "Exposure by sector and by supplier country, with a 35% warning (AC 4.9)")
    public Concentration concentration(@PathVariable Long portfolioId) {
        portfolioService.getOwned(portfolioId, currentUser.id());
        return riskQueryService.concentration(portfolioId, currentUser.id());
    }

    @PostMapping("/rescore")
    @Operation(summary = "Recompute scores from current relationships without refetching filings")
    public Summary rescore(@PathVariable Long portfolioId) {
        portfolioService.getOwned(portfolioId, currentUser.id());
        riskScoringService.rescorePortfolio(portfolioId, currentUser.id(), "Manual rescore");
        return riskQueryService.summary(portfolioId, currentUser.id());
    }
}
