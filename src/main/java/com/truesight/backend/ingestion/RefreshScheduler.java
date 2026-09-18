package com.truesight.backend.ingestion;

import com.truesight.backend.domain.Portfolio;
import com.truesight.backend.ingestion.news.NewsIngestionService;
import com.truesight.backend.repository.PortfolioRepository;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * AC 8.1: "Scheduled refresh runs daily by default and can be triggered with
 * 'Refresh now'." The daily job and the button call the same two services, so
 * there is one refresh code path, not two that can drift.
 *
 * <p>Runs at 06:00 server time — after SEC's overnight EDGAR processing, before a
 * working day. Per portfolio, per owner, so audit-log entries are attributed to the
 * portfolio's owner even though nobody clicked anything. Any one portfolio's failure
 * is logged and the loop continues; the failure surfaces on that portfolio's next
 * dashboard load via the LLM health banner and per-holding status, never as a crash
 * that stops other portfolios refreshing.
 */
@Component
public class RefreshScheduler {

    private static final Logger log = LoggerFactory.getLogger(RefreshScheduler.class);

    private final PortfolioRepository portfolioRepository;
    private final PortfolioIngestionService ingestionService;
    private final NewsIngestionService newsIngestionService;
    private final AtomicReference<Instant> lastScheduledRunAt = new AtomicReference<>();

    public RefreshScheduler(PortfolioRepository portfolioRepository, PortfolioIngestionService ingestionService,
                            NewsIngestionService newsIngestionService) {
        this.portfolioRepository = portfolioRepository;
        this.ingestionService = ingestionService;
        this.newsIngestionService = newsIngestionService;
    }

    @Scheduled(cron = "0 0 6 * * *")
    public void dailyRefresh() {
        lastScheduledRunAt.set(Instant.now());
        for (Portfolio portfolio : portfolioRepository.findAll()) {
            try {
                var filings = ingestionService.refreshChangedFilings(portfolio.getOwner(), portfolio);
                var news = newsIngestionService.refresh(portfolio.getOwner(), portfolio);
                log.info("Daily refresh portfolio {}: {} filings re-analysed, {} alerts created",
                        portfolio.getId(), filings.reanalysed(), news.alertsCreated());
            } catch (Exception e) {
                log.warn("Daily refresh failed for portfolio {} (previous results kept)", portfolio.getId(), e);
            }
        }
    }

    public Instant lastScheduledRunAt() {
        return lastScheduledRunAt.get();
    }
}
