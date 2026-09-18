package com.truesight.backend.web;

import com.truesight.backend.domain.CoverageStatus;
import com.truesight.backend.domain.Portfolio;
import com.truesight.backend.domain.User;
import com.truesight.backend.repository.HoldingRepository;
import com.truesight.backend.repository.AlertRepository;
import com.truesight.backend.repository.RiskAssessmentRepository;
import com.truesight.backend.repository.UserRepository;
import com.truesight.backend.risk.GraphAssemblyService;
import com.truesight.backend.risk.RiskQueryService;
import com.truesight.backend.security.CurrentUser;
import com.truesight.backend.service.AlertService;
import com.truesight.backend.service.PortfolioService;
import com.truesight.backend.web.dto.DashboardResponse;
import com.truesight.backend.web.dto.DashboardResponse.Coverage;
import com.truesight.backend.web.dto.DashboardResponse.SinceLastVisit;
import com.truesight.backend.web.dto.RiskResponses.RiskItem;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.List;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Epic 3: one payload with everything the dashboard shows, each section timestamped. */
@RestController
@RequestMapping("/api/portfolios/{portfolioId}/dashboard")
@Tag(name = "Dashboard", description = "Composite score, top risks, recent alerts, coverage, since-last-visit (Epic 3).")
public class DashboardController {

    private final PortfolioService portfolioService;
    private final RiskQueryService riskQueryService;
    private final AlertService alertService;
    private final HoldingRepository holdingRepository;
    private final AlertRepository alertRepository;
    private final RiskAssessmentRepository riskAssessmentRepository;
    private final GraphAssemblyService graphAssemblyService;
    private final UserRepository userRepository;
    private final CurrentUser currentUser;

    public DashboardController(PortfolioService portfolioService, RiskQueryService riskQueryService,
                               AlertService alertService, HoldingRepository holdingRepository,
                               AlertRepository alertRepository, RiskAssessmentRepository riskAssessmentRepository,
                               GraphAssemblyService graphAssemblyService, UserRepository userRepository,
                               CurrentUser currentUser) {
        this.portfolioService = portfolioService;
        this.riskQueryService = riskQueryService;
        this.alertService = alertService;
        this.holdingRepository = holdingRepository;
        this.alertRepository = alertRepository;
        this.riskAssessmentRepository = riskAssessmentRepository;
        this.graphAssemblyService = graphAssemblyService;
        this.userRepository = userRepository;
        this.currentUser = currentUser;
    }

    @GetMapping
    @Operation(summary = "Dashboard payload (AC 3.1, 3.3, 3.4)")
    @Transactional(readOnly = true)
    public DashboardResponse dashboard(@PathVariable Long portfolioId) {
        Long userId = currentUser.id();
        Portfolio portfolio = portfolioService.getOwned(portfolioId, userId);

        List<RiskItem> topRisks = riskQueryService.list(portfolioId, userId, null, null).stream()
                .filter(i -> i.score() != null)
                .limit(5)
                .toList();

        long total = holdingRepository.countByPortfolioIdAndRemovedFalse(portfolioId);
        Coverage coverage = new Coverage(
                holdingRepository.countByPortfolioIdAndRemovedFalseAndCoverageStatus(portfolioId, CoverageStatus.COVERED),
                total,
                holdingRepository.countByPortfolioIdAndRemovedFalseAndCoverageStatus(portfolioId, CoverageStatus.PENDING),
                holdingRepository.countByPortfolioIdAndRemovedFalseAndCoverageStatus(portfolioId, CoverageStatus.FAILED),
                holdingRepository.countByPortfolioIdAndRemovedFalseAndCoverageStatus(portfolioId, CoverageStatus.NO_SEC_FILINGS),
                portfolio.getLastAnalysedAt());

        // AC 3.4: counts since the login BEFORE this one. Null on a first-ever login —
        // "since your last visit" has no meaning yet, and the DTO says so with nulls
        // rather than counting from the beginning of time.
        User user = userRepository.getReferenceById(userId);
        Instant since = user.getPreviousLoginAt();
        SinceLastVisit sinceLastVisit;
        if (since == null) {
            sinceLastVisit = new SinceLastVisit(null, 0, 0, 0);
        } else {
            long newRelationships = graphAssemblyService.assemble(portfolioId, userId, false).edges().stream()
                    .filter(e -> e.relationship.getUpdatedAt() != null && e.relationship.getUpdatedAt().isAfter(since))
                    .count();
            sinceLastVisit = new SinceLastVisit(since,
                    alertRepository.countByPortfolioIdAndCreatedAtAfter(portfolioId, since),
                    riskAssessmentRepository.countByHolding_Portfolio_IdAndAssessedAtAfter(portfolioId, since),
                    newRelationships);
        }

        return new DashboardResponse(
                portfolio.getId(), portfolio.getName(),
                riskQueryService.summary(portfolioId, userId),
                topRisks,
                alertService.recent(portfolioId, userId, 5),
                coverage,
                sinceLastVisit,
                Instant.now());
    }
}
