package com.truesight.backend.web;

import com.truesight.backend.domain.RiskSeverity;
import com.truesight.backend.risk.GraphAssemblyService;
import com.truesight.backend.risk.RiskQueryService;
import com.truesight.backend.security.CurrentUser;
import com.truesight.backend.service.AlertService;
import com.truesight.backend.service.PortfolioService;
import com.truesight.backend.web.dto.AlertResponse;
import com.truesight.backend.web.dto.GraphResponse;
import com.truesight.backend.web.dto.RiskResponses.RiskItem;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Epic 11: "a summary of my top risks with reasoning and evidence, framed as areas to
 * investigate" and "suggest what to look into next".
 *
 * <p>Deliberately NOT an LLM call. Everything here is derived from persisted scores,
 * factors, shared-supplier counts and open alerts — each item links to the risk or
 * relationship it came from, so the reader can click through to the excerpt (design
 * principle 2). The wording is fixed template text written the way a careful analyst
 * would write it: "investigate", "verify", "consider reviewing the filing". No buy,
 * sell or hedge language anywhere (AC 11.1), and a disclaimer travels with every
 * response rather than living only on one screen.
 */
@RestController
@RequestMapping("/api/portfolios/{portfolioId}/recommendations")
@Tag(name = "Recommendations", description = "Research prompts derived from scores and evidence. Not advice (Epic 11).")
public class RecommendationController {

    public static final String DISCLAIMER =
            "TrueSight provides research support, not investment advice. Items below are areas to investigate, "
            + "derived from SEC filings and public news as analysed by an AI model with verbatim-excerpt "
            + "verification. Verify sources before relying on any finding.";

    private final PortfolioService portfolioService;
    private final RiskQueryService riskQueryService;
    private final GraphAssemblyService graphAssemblyService;
    private final AlertService alertService;
    private final CurrentUser currentUser;

    public RecommendationController(PortfolioService portfolioService, RiskQueryService riskQueryService,
                                    GraphAssemblyService graphAssemblyService, AlertService alertService,
                                    CurrentUser currentUser) {
        this.portfolioService = portfolioService;
        this.riskQueryService = riskQueryService;
        this.graphAssemblyService = graphAssemblyService;
        this.alertService = alertService;
        this.currentUser = currentUser;
    }

    public record Recommendation(String kind, String title, String reasoning, List<String> evidence,
                                 Long relationshipId, Long holdingId, Long companyId, RiskSeverity severity) {
    }

    public record RecommendationsResponse(List<Recommendation> topRisks, List<Recommendation> lookIntoNext,
                                          String disclaimer, Instant generatedAt) {
    }

    @GetMapping
    @Operation(summary = "Top risks to investigate, and suggested next steps (AC 11.1, 11.2)")
    public RecommendationsResponse recommendations(@PathVariable Long portfolioId) {
        Long userId = currentUser.id();
        portfolioService.getOwned(portfolioId, userId);

        List<Recommendation> top = new ArrayList<>();
        for (RiskItem item : riskQueryService.list(portfolioId, userId, null, null)) {
            if (item.score() == null || top.size() >= 5) {
                continue;
            }
            String title = "RELATIONSHIP".equals(item.kind())
                    ? "Investigate the dependency " + item.title()
                    : "Review " + item.title() + "'s upstream exposure";
            String reasoning = item.severity() + " (" + item.score() + "/100). Affects "
                    + item.affectedHoldings().size() + " holding(s), " + item.totalWeightExposed() + "% of portfolio weight."
                    + (item.trend().equals("RISING") ? " Score has risen since the previous assessment." : "");
            top.add(new Recommendation(item.kind(), title, reasoning, item.factors(),
                    item.relationshipId(), item.holdingId(), null, item.severity()));
        }

        List<Recommendation> next = new ArrayList<>();
        GraphResponse graph = graphAssemblyService.buildResponse(portfolioId, userId, false);
        for (GraphResponse.SharedSupplier s : graph.sharedSuppliers()) {
            next.add(new Recommendation("SHARED_SUPPLIER",
                    "Verify concentration through " + s.name(),
                    s.holdingCount() + " holdings (" + String.join(", ", s.holdingTickers()) + ", "
                            + s.combinedWeightPercent() + "% combined weight) depend on this one supplier. "
                            + "Consider reviewing each holding's filing language on alternatives.",
                    List.of("Shared-supplier count from the graph; see each relationship's excerpts."),
                    null, null, s.companyId(), null));
            if (next.size() >= 3) {
                break;
            }
        }
        long unverified = graph.edges().stream().filter(e -> e.reviewStatus() == com.truesight.backend.domain.ReviewStatus.PENDING).count();
        if (unverified > 0) {
            next.add(new Recommendation("REVIEW_PENDING",
                    "Confirm or reject " + unverified + " unreviewed relationship(s)",
                    "AI-extracted relationships carry a confidence score but no analyst judgement yet. "
                            + "Confirming or rejecting them sharpens every downstream score.",
                    List.of("Each relationship's evidence panel shows the verbatim filing excerpt."),
                    null, null, null, null));
        }
        long stale = graph.edges().stream().filter(GraphResponse.GraphEdge::noLongerDisclosed).count();
        if (stale > 0) {
            next.add(new Recommendation("STALE",
                    stale + " relationship(s) no longer disclosed in the latest filing",
                    "These are excluded from scoring unless confirmed. Check whether the dependency ended or was merely omitted.",
                    List.of("Compare the latest filing's supplier language with the prior year's."),
                    null, null, null, null));
        }
        List<AlertResponse> alerts = alertService.recent(portfolioId, userId, 3);
        for (AlertResponse a : alerts) {
            next.add(new Recommendation("ALERT",
                    "Read: " + a.headline(),
                    (a.summary() == null ? "" : a.summary() + " ") + "Affects " + a.affectedHoldings().size() + " holding(s).",
                    a.sources().stream().map(AlertResponse.Source::url).toList(),
                    null, null, a.sourceCompanyId(), null));
        }

        return new RecommendationsResponse(top, next, DISCLAIMER, Instant.now());
    }
}
