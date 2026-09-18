package com.truesight.backend.web;

import com.truesight.backend.domain.Portfolio;
import com.truesight.backend.domain.User;
import com.truesight.backend.ingestion.PortfolioIngestionService;
import com.truesight.backend.ingestion.RefreshScheduler;
import com.truesight.backend.ingestion.news.NewsIngestionService;
import com.truesight.backend.repository.UserRepository;
import com.truesight.backend.security.CurrentUser;
import com.truesight.backend.service.PortfolioService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Epic 8: "Refresh now" and the schedule's status. Same code path as the daily job. */
@RestController
@RequestMapping("/api/portfolios/{portfolioId}/refresh")
@Tag(name = "Refresh", description = "Re-analyse changed filings and fetch news (Epic 8).")
public class RefreshController {

    private final PortfolioService portfolioService;
    private final PortfolioIngestionService ingestionService;
    private final NewsIngestionService newsIngestionService;
    private final RefreshScheduler scheduler;
    private final UserRepository userRepository;
    private final CurrentUser currentUser;

    public RefreshController(PortfolioService portfolioService, PortfolioIngestionService ingestionService,
                             NewsIngestionService newsIngestionService, RefreshScheduler scheduler,
                             UserRepository userRepository, CurrentUser currentUser) {
        this.portfolioService = portfolioService;
        this.ingestionService = ingestionService;
        this.newsIngestionService = newsIngestionService;
        this.scheduler = scheduler;
        this.userRepository = userRepository;
        this.currentUser = currentUser;
    }

    public record RefreshResult(PortfolioIngestionService.RefreshOutcome filings,
                                NewsIngestionService.RefreshOutcome news, Instant ranAt) {
    }

    @PostMapping
    @Operation(summary = "Refresh now (AC 8.1)",
            description = "Re-analyses only holdings whose primary SEC filing has a new accession number, flags relationships "
                    + "the new filing no longer mentions (AC 5.3), then fetches news. A failure keeps previous results.")
    public RefreshResult refreshNow(@PathVariable Long portfolioId) {
        Portfolio portfolio = portfolioService.getOwned(portfolioId, currentUser.id());
        User user = userRepository.getReferenceById(currentUser.id());
        var filings = ingestionService.refreshChangedFilings(user, portfolio);
        var news = newsIngestionService.refresh(user, portfolio);
        return new RefreshResult(filings, news, Instant.now());
    }

    public record ScheduleStatus(String cron, String description, Instant lastScheduledRunAt) {
    }

    @GetMapping("/schedule")
    @Operation(summary = "When the automatic refresh runs")
    public ScheduleStatus schedule(@PathVariable Long portfolioId) {
        portfolioService.getOwned(portfolioId, currentUser.id());
        return new ScheduleStatus("0 0 6 * * *", "Daily at 06:00 server time", scheduler.lastScheduledRunAt());
    }
}
