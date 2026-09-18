package com.truesight.backend.web;

import com.truesight.backend.domain.Portfolio;
import com.truesight.backend.domain.User;
import com.truesight.backend.ingestion.news.NewsIngestionService;
import com.truesight.backend.ingestion.news.NewsIngestionService.RefreshOutcome;
import com.truesight.backend.repository.UserRepository;
import com.truesight.backend.risk.RiskScoringService;
import com.truesight.backend.security.CurrentUser;
import com.truesight.backend.service.PortfolioService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Epic 7 creation side: "Refresh news now". The scheduled daily run (AC 8.1) calls the same service. */
@RestController
@RequestMapping("/api/portfolios/{portfolioId}/news")
@Tag(name = "News", description = "Fetch and analyse news for holdings and tier-1 suppliers (Epic 7).")
public class NewsController {

    private final PortfolioService portfolioService;
    private final NewsIngestionService newsIngestionService;
    private final RiskScoringService riskScoringService;
    private final UserRepository userRepository;
    private final CurrentUser currentUser;

    public NewsController(PortfolioService portfolioService, NewsIngestionService newsIngestionService,
                          RiskScoringService riskScoringService, UserRepository userRepository, CurrentUser currentUser) {
        this.portfolioService = portfolioService;
        this.newsIngestionService = newsIngestionService;
        this.riskScoringService = riskScoringService;
        this.userRepository = userRepository;
        this.currentUser = currentUser;
    }

    @PostMapping("/refresh")
    @Operation(summary = "Fetch recent news for every holding and tier-1 supplier and turn qualifying articles into alerts",
            description = "Articles are analysed once (keyed by URL); the same story across outlets collapses into one alert. "
                    + "Threshold: relevance >= 0.25 and sentiment <= -0.25 (see NewsIngestionService). Rescores afterwards "
                    + "because open negative alerts are a risk factor.")
    public RefreshOutcome refresh(@PathVariable Long portfolioId) {
        Portfolio portfolio = portfolioService.getOwned(portfolioId, currentUser.id());
        User user = userRepository.getReferenceById(currentUser.id());
        RefreshOutcome outcome = newsIngestionService.refresh(user, portfolio);
        if (outcome.alertsCreated() > 0) {
            riskScoringService.rescorePortfolio(portfolioId, currentUser.id(), "News refresh: " + outcome.alertsCreated() + " new alert(s)");
        }
        return outcome;
    }
}
