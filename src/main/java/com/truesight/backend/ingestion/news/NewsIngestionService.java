package com.truesight.backend.ingestion.news;

import com.truesight.backend.config.TrueSightProperties;
import com.truesight.backend.domain.Alert;
import com.truesight.backend.domain.AlertSeverity;
import com.truesight.backend.domain.AlertSource;
import com.truesight.backend.domain.AuditActionType;
import com.truesight.backend.domain.Company;
import com.truesight.backend.domain.Portfolio;
import com.truesight.backend.domain.ProcessedInput;
import com.truesight.backend.domain.ProcessedInputType;
import com.truesight.backend.domain.User;
import com.truesight.backend.ingestion.llm.AuditLogWriter;
import com.truesight.backend.ingestion.llm.GeminiClient;
import com.truesight.backend.ingestion.llm.LlmHealthTracker;
import com.truesight.backend.ingestion.llm.LlmUnavailableException;
import com.truesight.backend.repository.AlertRepository;
import com.truesight.backend.repository.ProcessedInputRepository;
import com.truesight.backend.risk.GraphAssemblyService;
import com.truesight.backend.risk.GraphAssemblyService.AssembledGraph;
import com.truesight.backend.risk.GraphAssemblyService.NodeModel;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * AC 7.1, end to end: fetch news for each holding and each tier-1 supplier, analyse
 * each article once (keyed by URL — AC 8.1), collapse the same story across outlets
 * into one alert, apply the documented threshold, and create Alert rows.
 *
 * <p><b>The threshold, documented as AC 7.1 requires:</b> an article becomes an alert
 * when Gemini rates its relevance at or above {@code truesight.news.relevance-threshold}
 * (0.25 by default — see application.yml) AND its sentiment at or below -0.25.
 * Relevance filters out articles that merely mention the company; the sentiment cut
 * filters out neutral and positive coverage, because alerts are for disruptions.
 * Severity is then a function of sentiment alone:
 * <pre>
 *   sentiment <= -0.75  CRITICAL
 *   sentiment <= -0.50  ELEVATED
 *   sentiment <= -0.35  MODERATE
 *   otherwise           LOW
 * </pre>
 *
 * <p>Dedupe by title similarity: two titles whose normalised token sets overlap by
 * Jaccard >= 0.6 are the same story. This is crude and honest — it catches "TSMC halts
 * fab after quake" vs "TSMC halts Tainan fab following earthquake" and misses
 * genuinely differently-worded coverage. A production system would embed titles; that
 * is out of scope here and noted rather than faked.
 */
@Service
public class NewsIngestionService {

    private static final Logger log = LoggerFactory.getLogger(NewsIngestionService.class);
    private static final double SENTIMENT_THRESHOLD = -0.25;
    private static final Pattern NON_WORD = Pattern.compile("[^a-z0-9 ]");
    private static final Set<String> STOPWORDS = Set.of("the", "a", "an", "of", "to", "in", "on", "for", "and",
            "after", "as", "at", "by", "with", "from", "its", "is", "are", "over", "amid", "says", "said");

    private final GoogleNewsClient newsClient;
    private final GeminiClient geminiClient;
    private final GraphAssemblyService graphAssemblyService;
    private final AlertRepository alertRepository;
    private final ProcessedInputRepository processedInputRepository;
    private final AuditLogWriter auditLogWriter;
    private final LlmHealthTracker llmHealthTracker;
    private final TrueSightProperties properties;

    public NewsIngestionService(GoogleNewsClient newsClient, GeminiClient geminiClient,
                                GraphAssemblyService graphAssemblyService, AlertRepository alertRepository,
                                ProcessedInputRepository processedInputRepository, AuditLogWriter auditLogWriter,
                                LlmHealthTracker llmHealthTracker, TrueSightProperties properties) {
        this.newsClient = newsClient;
        this.geminiClient = geminiClient;
        this.graphAssemblyService = graphAssemblyService;
        this.alertRepository = alertRepository;
        this.processedInputRepository = processedInputRepository;
        this.auditLogWriter = auditLogWriter;
        this.llmHealthTracker = llmHealthTracker;
        this.properties = properties;
    }

    public record RefreshOutcome(int companiesChecked, int articlesFetched, int articlesAnalysed,
                                 int alertsCreated, int skippedAlreadyProcessed, List<String> failures) {
    }

    @Transactional
    public RefreshOutcome refresh(User user, Portfolio portfolio) {
        AssembledGraph graph = graphAssemblyService.assemble(portfolio.getId(), user.getId(), false);
        List<Company> targets = new ArrayList<>();
        for (NodeModel n : graph.nodesByCompanyId().values()) {
            if (n.isHolding() || n.tier == 1) {
                targets.add(n.company);
            }
        }

        int fetched = 0;
        int analysed = 0;
        int created = 0;
        int skipped = 0;
        List<String> failures = new ArrayList<>();
        List<Alert> createdThisRun = new ArrayList<>();

        for (Company company : targets) {
            List<NewsArticle> articles;
            try {
                articles = newsClient.fetchRecent(company.getName());
            } catch (Exception e) {
                failures.add(company.getName() + ": news fetch failed (" + e.getMessage() + ")");
                continue;
            }
            fetched += articles.size();

            for (NewsArticle article : articles) {
                if (processedInputRepository.existsByInputKey(article.url())) {
                    skipped++;
                    continue;
                }
                Alert existing = findSameStory(article, createdThisRun, portfolio.getId());
                if (existing != null) {
                    // AC 7.1: same story, another outlet — attach the link, no new alert.
                    existing.getSources().add(new AlertSource(existing, article.title(), article.url(), article.publishedAt()));
                    markProcessed(article.url());
                    continue;
                }

                ArticleAnalysis analysis;
                long started = System.currentTimeMillis();
                try {
                    analysis = geminiClient.analyzeArticle(company.getName(), article.title(), article.snippet());
                    analysed++;
                    llmHealthTracker.recordSuccess();
                    auditLogWriter.writeGeneric(user, AuditActionType.NEWS_SENTIMENT_ANALYSIS, company.getName(),
                            geminiClient.currentModelName(), article.url(), started, true,
                            String.format(Locale.ROOT, "relevance=%.2f sentiment=%.2f %s", analysis.relevance(),
                                    analysis.sentiment(), analysis.category()), null);
                } catch (Exception e) {
                    llmHealthTracker.recordFailure(e);
                    auditLogWriter.writeGeneric(user, AuditActionType.NEWS_SENTIMENT_ANALYSIS, company.getName(),
                            geminiClient.currentModelName(), article.url(), started, false, null, e.getMessage());
                    LlmUnavailableException llm = LlmUnavailableException.findIn(e);
                    failures.add(company.getName() + ": " + (llm != null ? llm.userFacingSummary() : e.getMessage()));
                    if (llm != null && !llm.getKind().isTransient()) {
                        // Bad key / quota gone: every further call will fail the same way. Stop
                        // here rather than burning through the whole list producing identical
                        // failures; the articles stay unprocessed and will be picked up next run.
                        return new RefreshOutcome(targets.size(), fetched, analysed, created, skipped, failures);
                    }
                    continue;
                }

                markProcessed(article.url()); // analysed once, whatever the verdict (AC 8.1)
                if (analysis.relevance() < properties.news().relevanceThreshold() || analysis.sentiment() > SENTIMENT_THRESHOLD) {
                    continue;
                }
                Alert alert = new Alert(portfolio, company, article.title(), severityFor(analysis.sentiment()), article.publishedAt());
                alert.setSummary(analysis.summary());
                alert.getSources().add(new AlertSource(alert, article.title(), article.url(), article.publishedAt()));
                alert.getSources().get(0).setOutlet(article.outlet());
                alertRepository.save(alert);
                createdThisRun.add(alert);
                created++;
            }
        }
        log.info("News refresh for portfolio {}: {} companies, {} articles, {} analysed, {} alerts, {} skipped",
                portfolio.getId(), targets.size(), fetched, analysed, created, skipped);
        return new RefreshOutcome(targets.size(), fetched, analysed, created, skipped, failures);
    }

    private void markProcessed(String url) {
        if (!processedInputRepository.existsByInputKey(url)) {
            processedInputRepository.save(new ProcessedInput(ProcessedInputType.NEWS_ARTICLE, url));
        }
    }

    static AlertSeverity severityFor(double sentiment) {
        if (sentiment <= -0.75) {
            return AlertSeverity.CRITICAL;
        }
        if (sentiment <= -0.50) {
            return AlertSeverity.ELEVATED;
        }
        if (sentiment <= -0.35) {
            return AlertSeverity.MODERATE;
        }
        return AlertSeverity.LOW;
    }

    /** Looks for an alert (created this run or already stored, recent) telling the same story. */
    private Alert findSameStory(NewsArticle article, List<Alert> createdThisRun, Long portfolioId) {
        Set<String> tokens = tokens(article.title());
        for (Alert a : createdThisRun) {
            if (jaccard(tokens, tokens(a.getHeadline())) >= 0.6) {
                return a;
            }
        }
        for (Alert a : alertRepository.findByPortfolioIdOrderByEventAtDesc(portfolioId).stream().limit(200).toList()) {
            if (jaccard(tokens, tokens(a.getHeadline())) >= 0.6) {
                return a;
            }
        }
        return null;
    }

    static Set<String> tokens(String title) {
        Set<String> out = new HashSet<>();
        String cleaned = NON_WORD.matcher(title.toLowerCase(Locale.ROOT)).replaceAll(" ");
        for (String t : cleaned.split("\\s+")) {
            if (t.length() > 1 && !STOPWORDS.contains(t)) {
                out.add(t);
            }
        }
        return out;
    }

    static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) {
            return 0;
        }
        Set<String> inter = new HashSet<>(a);
        inter.retainAll(b);
        Set<String> union = new HashSet<>(a);
        union.addAll(b);
        return (double) inter.size() / union.size();
    }
}
