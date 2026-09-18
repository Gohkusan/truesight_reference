package com.truesight.backend.risk;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.truesight.backend.domain.Alert;
import com.truesight.backend.domain.AlertSeverity;
import com.truesight.backend.domain.AlertStatus;
import com.truesight.backend.domain.CoverageStatus;
import com.truesight.backend.domain.Criticality;
import com.truesight.backend.domain.Holding;
import com.truesight.backend.domain.Relationship;
import com.truesight.backend.domain.RiskAssessment;
import com.truesight.backend.domain.RiskSeverity;
import com.truesight.backend.repository.AlertRepository;
import com.truesight.backend.repository.RelationshipRepository;
import com.truesight.backend.repository.RiskAssessmentRepository;
import com.truesight.backend.risk.GraphAssemblyService.AssembledGraph;
import com.truesight.backend.risk.GraphAssemblyService.EdgeModel;
import com.truesight.backend.risk.GraphAssemblyService.NodeModel;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Epic 6: a 0-100 risk score per relationship and per holding, each with the list of
 * factors that produced it (AC 6.1: "the explanation lists the factors used:
 * single-source, shared supplier, negative news, geographic concentration").
 *
 * <p>Design rules this class enforces, each traceable to the spec:
 * <ul>
 *   <li><b>Additive, inspectable arithmetic.</b> Every point of score comes from a
 *       named factor that is also returned as text. There is no hidden weighting.</li>
 *   <li><b>UNKNOWN is real.</b> A holding with no covered filing or no counted
 *       relationships gets severity UNKNOWN and a null score — never LOW. UNKNOWN
 *       holdings are excluded from the composite and counted separately (AC 6.1).</li>
 *   <li><b>Append-only history.</b> A new RiskAssessment row is written only when the
 *       score or factors actually changed (or none exists yet), with a changeReason
 *       describing the difference — that is what AC 6.4 (rising/falling/new) and
 *       AC 8.2 (what input changed, before, after, when) read from.</li>
 *   <li><b>Nothing fabricated.</b> With no LLM result there are no relationships, so
 *       there is no score. This class never invents a number to fill a gap.</li>
 * </ul>
 */
@Service
public class RiskScoringService {

    private static final Logger log = LoggerFactory.getLogger(RiskScoringService.class);

    /** AC 4.9: concentration warning threshold, shared with the concentration view. */
    public static final double GEO_CONCENTRATION_WARN = 0.35;

    private final GraphAssemblyService graphAssemblyService;
    private final ConfidenceScoringService confidenceScoringService;
    private final RiskAssessmentRepository riskAssessmentRepository;
    private final RelationshipRepository relationshipRepository;
    private final AlertRepository alertRepository;
    private final ObjectMapper objectMapper;

    public RiskScoringService(GraphAssemblyService graphAssemblyService,
                              ConfidenceScoringService confidenceScoringService,
                              RiskAssessmentRepository riskAssessmentRepository,
                              RelationshipRepository relationshipRepository,
                              AlertRepository alertRepository,
                              ObjectMapper objectMapper) {
        this.graphAssemblyService = graphAssemblyService;
        this.confidenceScoringService = confidenceScoringService;
        this.riskAssessmentRepository = riskAssessmentRepository;
        this.relationshipRepository = relationshipRepository;
        this.alertRepository = alertRepository;
        this.objectMapper = objectMapper;
    }

    public record Scored(Integer score, RiskSeverity severity, List<String> factors) {
    }

    public record PortfolioRiskSummary(Integer compositeScore, RiskSeverity band,
                                       int scoredHoldings, int unknownHoldings, int totalHoldings) {
    }

    public static RiskSeverity bandOf(Integer score) {
        if (score == null) {
            return RiskSeverity.UNKNOWN;
        }
        if (score >= 75) {
            return RiskSeverity.CRITICAL;
        }
        if (score >= 50) {
            return RiskSeverity.ELEVATED;
        }
        if (score >= 25) {
            return RiskSeverity.MODERATE;
        }
        return RiskSeverity.LOW;
    }

    /**
     * Rescores every counted relationship and every holding in the portfolio.
     * {@code trigger} is a short description of what prompted the rescore ("Initial
     * analysis", "New 10-K 0000320193-25-000045", "Relationship rejected by user",
     * "Daily refresh") and is recorded in each changed row's changeReason so AC 8.2's
     * "what input changed" is answerable from the row itself.
     */
    @Transactional
    public PortfolioRiskSummary rescorePortfolio(Long portfolioId, Long userId, String trigger) {
        AssembledGraph graph = graphAssemblyService.assemble(portfolioId, userId, false);
        LocalDate today = LocalDate.now();

        Map<Long, Double> geoShare = supplierCountryShares(graph);
        Map<Long, Integer> openNegativeAlertsBySupplier = openNegativeAlerts(portfolioId);

        Map<Long, List<Scored>> edgeScoresByBuyer = new HashMap<>();
        int changed = 0;

        for (EdgeModel e : graph.edges()) {
            Relationship r = e.relationship;

            ConfidenceScoringService.Result conf = confidenceScoringService.score(r, today);
            if (!Objects.equals(r.getConfidenceScore(), conf.score())) {
                r.setConfidenceScore(conf.score());
                relationshipRepository.save(r);
            }

            if (!e.countsTowardRisk()) {
                continue;
            }
            NodeModel supplier = graph.nodesByCompanyId().get(r.getFromCompany().getId());
            Scored scored = scoreRelationship(r, supplier, geoShare, openNegativeAlertsBySupplier, conf);
            if (appendIfChanged(RiskAssessment.forRelationship(r),
                    riskAssessmentRepository.findTopByRelationshipIdOrderByAssessedAtDesc(r.getId()), scored, trigger)) {
                changed++;
            }
            edgeScoresByBuyer.computeIfAbsent(r.getToCompany().getId(), k -> new ArrayList<>()).add(scored);
        }

        int scoredHoldings = 0;
        int unknownHoldings = 0;
        BigDecimal weightedSum = BigDecimal.ZERO;
        BigDecimal weightTotal = BigDecimal.ZERO;
        int unweightedSum = 0;

        for (NodeModel n : graph.nodesByCompanyId().values()) {
            if (!n.isHolding()) {
                continue;
            }
            Holding h = n.holding;
            Scored scored = scoreHolding(h, edgeScoresByBuyer.getOrDefault(h.getCompany().getId(), List.of()));
            if (appendIfChanged(RiskAssessment.forHolding(h),
                    riskAssessmentRepository.findTopByHoldingIdOrderByAssessedAtDesc(h.getId()), scored, trigger)) {
                changed++;
            }
            if (scored.score() == null) {
                unknownHoldings++;
                continue;
            }
            scoredHoldings++;
            unweightedSum += scored.score();
            if (h.getWeightPercent() != null) {
                weightedSum = weightedSum.add(h.getWeightPercent().multiply(BigDecimal.valueOf(scored.score())));
                weightTotal = weightTotal.add(h.getWeightPercent());
            }
        }

        Integer composite = null;
        if (scoredHoldings > 0) {
            // Weight-averaged when weights exist; plain average otherwise. Never mixes
            // the two silently: if ANY scored holding lacks a weight, fall back to
            // unweighted for all, so a partially-weighted portfolio doesn't produce a
            // number that looks weighted but isn't.
            composite = weightTotal.signum() > 0 && allScoredHaveWeights(graph, edgeScoresByBuyer)
                    ? weightedSum.divide(weightTotal, 0, RoundingMode.HALF_UP).intValue()
                    : unweightedSum / scoredHoldings;
        }
        log.info("Rescored portfolio {} ({}): {} assessment rows appended", portfolioId, trigger, changed);
        return new PortfolioRiskSummary(composite, bandOf(composite), scoredHoldings, unknownHoldings,
                scoredHoldings + unknownHoldings);
    }

    // ---- relationship score --------------------------------------------------------

    private Scored scoreRelationship(Relationship r, NodeModel supplier, Map<Long, Double> geoShare,
                                     Map<Long, Integer> openNegativeAlerts, ConfidenceScoringService.Result conf) {
        List<String> factors = new ArrayList<>();
        int score = 0;

        switch (r.getCriticality()) {
            case SINGLE_SOURCE -> { score += 40; factors.add("Single-source dependency: +40"); }
            case DUAL_SOURCE -> { score += 20; factors.add("Dual-source dependency: +20"); }
            case DIVERSIFIED -> { score += 5; factors.add("Diversified sourcing: +5"); }
        }

        int dependants = supplier == null ? 0 : supplier.dependantHoldingCompanyIds.size();
        if (dependants >= 2) {
            int pts = Math.min(25, 15 + 5 * (dependants - 2));
            score += pts;
            factors.add("Shared supplier: " + dependants + " of your holdings depend on "
                    + r.getFromCompany().getName() + ": +" + pts);
        }

        if (r.getDependencyPercent() != null && r.getDependencyPercent() >= 50) {
            score += 10;
            factors.add("Disclosed dependency " + r.getDependencyPercent() + "%: +10");
        }

        int negative = openNegativeAlerts.getOrDefault(r.getFromCompany().getId(), 0);
        if (negative > 0) {
            int pts = Math.min(20, 10 * negative);
            score += pts;
            factors.add("Negative news: " + negative + " open alert(s) on " + r.getFromCompany().getName() + ": +" + pts);
        }

        String country = r.getFromCompany().getCountry();
        if (country != null) {
            double share = geoShare.getOrDefault(companyKey(r), 0.0);
            if (share >= GEO_CONCENTRATION_WARN) {
                score += 10;
                factors.add(String.format("Geographic concentration: %.0f%% of supplier exposure in %s: +10",
                        share * 100, country));
            }
        }

        if (conf.band() == ConfidenceScoringService.Band.LOW) {
            factors.add("Note: low confidence (" + conf.score() + ") — evidence is thin; score unchanged");
        }

        score = Math.min(100, score);
        return new Scored(score, bandOf(score), factors);
    }

    private static Long companyKey(Relationship r) {
        return r.getFromCompany().getId();
    }

    // ---- holding score -------------------------------------------------------------

    /**
     * A holding's risk is dominated by its worst dependency, nudged by the rest:
     * max(edge scores) + 25% of the mean of the others, capped at 100. Max-dominant
     * because one single-source chokepoint is a real problem regardless of how many
     * diversified suppliers sit beside it; the nudge keeps "one bad edge" and "five
     * bad edges" distinguishable.
     */
    private Scored scoreHolding(Holding h, List<Scored> edgeScores) {
        List<String> factors = new ArrayList<>();
        if (h.getCoverageStatus() != CoverageStatus.COVERED) {
            factors.add("No analysed filing: coverage is " + h.getCoverageStatus());
            return new Scored(null, RiskSeverity.UNKNOWN, factors);
        }
        if (edgeScores.isEmpty()) {
            factors.add("Filing analysed but no verified supplier relationships found");
            return new Scored(null, RiskSeverity.UNKNOWN, factors);
        }
        int max = edgeScores.stream().mapToInt(Scored::score).max().orElse(0);
        double othersMean = edgeScores.stream().mapToInt(Scored::score).filter(s -> s != max).average().orElse(0);
        int score = (int) Math.min(100, Math.round(max + 0.25 * othersMean));
        factors.add("Highest dependency risk: " + max);
        if (edgeScores.size() > 1) {
            factors.add(String.format("%d further dependencies (mean %.0f): +%d",
                    edgeScores.size() - 1, othersMean, score - max));
        }
        // Surface the top edge's own factors so the holding's explanation is one click
        // from the evidence (design principle 2).
        edgeScores.stream().filter(s -> s.score() == max).findFirst()
                .ifPresent(top -> top.factors().stream().limit(3).forEach(f -> factors.add("  · " + f)));
        return new Scored(score, bandOf(score), factors);
    }

    // ---- inputs --------------------------------------------------------------------

    /** Share of total supplier dependant-weight sitting in each supplier's country, keyed by supplier company id. */
    private Map<Long, Double> supplierCountryShares(AssembledGraph graph) {
        Map<String, BigDecimal> byCountry = new HashMap<>();
        BigDecimal total = BigDecimal.ZERO;
        for (NodeModel n : graph.suppliers()) {
            if (n.company.getCountry() == null || n.dependantWeight.signum() == 0) {
                continue; // AC 4.9: unknown country is never guessed, so it never contributes
            }
            byCountry.merge(n.company.getCountry(), n.dependantWeight, BigDecimal::add);
            total = total.add(n.dependantWeight);
        }
        Map<Long, Double> shares = new HashMap<>();
        if (total.signum() == 0) {
            return shares;
        }
        for (NodeModel n : graph.suppliers()) {
            String c = n.company.getCountry();
            if (c != null && byCountry.containsKey(c)) {
                shares.put(n.company.getId(), byCountry.get(c).divide(total, 4, RoundingMode.HALF_UP).doubleValue());
            }
        }
        return shares;
    }

    private Map<Long, Integer> openNegativeAlerts(Long portfolioId) {
        Map<Long, Integer> counts = new HashMap<>();
        for (Alert a : alertRepository.findByPortfolioIdOrderByEventAtDesc(portfolioId)) {
            boolean open = a.getStatus() == AlertStatus.NEW || a.getStatus() == AlertStatus.REVIEWED;
            boolean negative = a.getSeverity() == AlertSeverity.ELEVATED || a.getSeverity() == AlertSeverity.CRITICAL;
            if (open && negative) {
                counts.merge(a.getSourceCompany().getId(), 1, Integer::sum);
            }
        }
        return counts;
    }

    private boolean allScoredHaveWeights(AssembledGraph graph, Map<Long, List<Scored>> edgeScoresByBuyer) {
        return graph.nodesByCompanyId().values().stream()
                .filter(NodeModel::isHolding)
                .filter(n -> n.holding.getCoverageStatus() == CoverageStatus.COVERED
                        && !edgeScoresByBuyer.getOrDefault(n.company.getId(), List.of()).isEmpty())
                .allMatch(n -> n.holding.getWeightPercent() != null);
    }

    // ---- append-only persistence ---------------------------------------------------

    /** Writes a new row only if something changed. Returns true if a row was written. */
    private boolean appendIfChanged(RiskAssessment fresh, Optional<RiskAssessment> previous, Scored scored, String trigger) {
        String factorsJson = toJson(scored.factors());
        if (previous.isPresent()) {
            RiskAssessment prev = previous.get();
            boolean sameScore = Objects.equals(prev.getScore(), scored.score());
            boolean sameFactors = Objects.equals(prev.getFactorsJson(), factorsJson);
            if (sameScore && sameFactors) {
                return false;
            }
            fresh.setChangeReason(describeChange(prev, scored, trigger));
        } else {
            fresh.setChangeReason("Initial assessment" + (trigger == null ? "" : " (" + trigger + ")"));
        }
        fresh.setScore(scored.score());
        fresh.setSeverity(scored.severity());
        fresh.setFactorsJson(factorsJson);
        riskAssessmentRepository.save(fresh);
        return true;
    }

    /** AC 8.2 / 6.4: what changed, before, after. Diffs the factor lists by their label (text before the colon). */
    private String describeChange(RiskAssessment prev, Scored now, String trigger) {
        List<String> before = fromJson(prev.getFactorsJson());
        List<String> added = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        for (String f : now.factors()) {
            if (before.stream().noneMatch(b -> label(b).equals(label(f)))) {
                added.add(label(f));
            }
        }
        for (String b : before) {
            if (now.factors().stream().noneMatch(f -> label(f).equals(label(b)))) {
                removed.add(label(b));
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Score ").append(prev.getScore() == null ? "Unknown" : prev.getScore())
          .append(" → ").append(now.score() == null ? "Unknown" : now.score());
        if (!added.isEmpty()) {
            sb.append("; added: ").append(String.join(", ", added));
        }
        if (!removed.isEmpty()) {
            sb.append("; removed: ").append(String.join(", ", removed));
        }
        if (trigger != null) {
            sb.append(" (").append(trigger).append(")");
        }
        return sb.length() > 1000 ? sb.substring(0, 997) + "..." : sb.toString();
    }

    private static String label(String factor) {
        int colon = factor.indexOf(':');
        return (colon > 0 ? factor.substring(0, colon) : factor).trim();
    }

    private String toJson(List<String> factors) {
        try {
            return objectMapper.writeValueAsString(factors);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    public List<String> fromJson(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (JsonProcessingException e) {
            return List.of(json);
        }
    }

    /** Public so other services can classify a stored criticality consistently. */
    public static boolean isSingleSource(Criticality c) {
        return c == Criticality.SINGLE_SOURCE;
    }
}
