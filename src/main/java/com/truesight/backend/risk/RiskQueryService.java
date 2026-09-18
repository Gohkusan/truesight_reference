package com.truesight.backend.risk;

import com.truesight.backend.domain.Holding;
import com.truesight.backend.domain.Portfolio;
import com.truesight.backend.domain.Relationship;
import com.truesight.backend.domain.RiskAssessment;
import com.truesight.backend.domain.RiskSeverity;
import com.truesight.backend.repository.PortfolioRepository;
import com.truesight.backend.repository.RiskAssessmentRepository;
import com.truesight.backend.risk.GraphAssemblyService.AssembledGraph;
import com.truesight.backend.risk.GraphAssemblyService.EdgeModel;
import com.truesight.backend.risk.GraphAssemblyService.NodeModel;
import com.truesight.backend.web.dto.RiskResponses.AffectedHolding;
import com.truesight.backend.web.dto.RiskResponses.Concentration;
import com.truesight.backend.web.dto.RiskResponses.RiskItem;
import com.truesight.backend.web.dto.RiskResponses.Slice;
import com.truesight.backend.web.dto.RiskResponses.Summary;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read side of Epic 6: turns persisted RiskAssessment rows plus the assembled graph
 * into the risk list, the composite summary, and the concentration view. Never
 * computes a score itself — that is RiskScoringService's job — so this class cannot
 * disagree with what was persisted.
 *
 * <p>AC 6.3 / design principle 4 ("rank before you render"): the list is returned
 * sorted most-severe first, UNKNOWN last, so a caller that renders it top-down gets
 * the right order without re-sorting.
 */
@Service
public class RiskQueryService {

    public static final String HOW_COMPUTED =
            "Composite = portfolio-weight-weighted average of each covered holding's risk score. "
            + "A holding's score = its riskiest verified dependency + 25% of the mean of its other "
            + "dependencies. Dependency scores add fixed points per factor: single-source +40, "
            + "dual-source +20, diversified +5, shared supplier +15 to +25, disclosed dependency ≥50% +10, "
            + "open negative alerts +10 each (max +20), supplier-country concentration ≥35% +10. "
            + "Holdings with no analysed filing or no verified relationships are Unknown and excluded.";

    private final GraphAssemblyService graphAssemblyService;
    private final RiskAssessmentRepository riskAssessmentRepository;
    private final RiskScoringService riskScoringService;
    private final PortfolioRepository portfolioRepository;

    public RiskQueryService(GraphAssemblyService graphAssemblyService,
                            RiskAssessmentRepository riskAssessmentRepository,
                            RiskScoringService riskScoringService,
                            PortfolioRepository portfolioRepository) {
        this.graphAssemblyService = graphAssemblyService;
        this.riskAssessmentRepository = riskAssessmentRepository;
        this.riskScoringService = riskScoringService;
        this.portfolioRepository = portfolioRepository;
    }

    @Transactional(readOnly = true)
    public Summary summary(Long portfolioId, Long userId) {
        AssembledGraph graph = graphAssemblyService.assemble(portfolioId, userId, false);
        int scored = 0;
        int unknown = 0;
        BigDecimal weightedSum = BigDecimal.ZERO;
        BigDecimal weightTotal = BigDecimal.ZERO;
        int plainSum = 0;
        boolean allWeighted = true;

        for (NodeModel n : graph.nodesByCompanyId().values()) {
            if (!n.isHolding()) {
                continue;
            }
            RiskAssessment latest = riskAssessmentRepository
                    .findTopByHoldingIdOrderByAssessedAtDesc(n.holding.getId()).orElse(null);
            if (latest == null || latest.getScore() == null) {
                unknown++;
                continue;
            }
            scored++;
            plainSum += latest.getScore();
            BigDecimal w = n.holding.getWeightPercent();
            if (w == null) {
                allWeighted = false;
            } else {
                weightedSum = weightedSum.add(w.multiply(BigDecimal.valueOf(latest.getScore())));
                weightTotal = weightTotal.add(w);
            }
        }
        Integer composite = null;
        if (scored > 0) {
            composite = (allWeighted && weightTotal.signum() > 0)
                    ? weightedSum.divide(weightTotal, 0, RoundingMode.HALF_UP).intValue()
                    : plainSum / scored;
        }
        Portfolio p = portfolioRepository.findById(portfolioId).orElse(null);
        return new Summary(composite, RiskScoringService.bandOf(composite), scored, unknown, scored + unknown,
                p == null ? null : p.getLastAnalysedAt(), HOW_COMPUTED);
    }

    /**
     * Every holding and every counted relationship as a ranked RiskItem. Optional
     * filters: minimum severity, and a holding id (returns that holding plus the
     * relationships that affect it) — AC 6.3.
     */
    @Transactional(readOnly = true)
    public List<RiskItem> list(Long portfolioId, Long userId, RiskSeverity minSeverity, Long holdingIdFilter) {
        AssembledGraph graph = graphAssemblyService.assemble(portfolioId, userId, false);
        List<RiskItem> items = new ArrayList<>();

        Map<Long, NodeModel> holdingsByCompany = new LinkedHashMap<>();
        for (NodeModel n : graph.nodesByCompanyId().values()) {
            if (n.isHolding()) {
                holdingsByCompany.put(n.company.getId(), n);
            }
        }

        for (NodeModel n : holdingsByCompany.values()) {
            Holding h = n.holding;
            if (holdingIdFilter != null && !h.getId().equals(holdingIdFilter)) {
                continue;
            }
            List<RiskAssessment> history = riskAssessmentRepository.findByHoldingIdOrderByAssessedAtAsc(h.getId());
            RiskItem item = toItem("HOLDING", h.getId(), null, h.getCompany().getName(), history,
                    List.of(new AffectedHolding(h.getId(), h.getCompany().getTicker(), h.getCompany().getName(), h.getWeightPercent())));
            items.add(item);
        }

        for (EdgeModel e : graph.edges()) {
            if (!e.countsTowardRisk()) {
                continue;
            }
            Relationship r = e.relationship;
            NodeModel supplier = graph.nodesByCompanyId().get(r.getFromCompany().getId());
            List<AffectedHolding> affected = affectedHoldings(supplier, graph, holdingsByCompany);
            if (holdingIdFilter != null && affected.stream().noneMatch(a -> a.holdingId().equals(holdingIdFilter))) {
                continue;
            }
            List<RiskAssessment> history = riskAssessmentRepository.findByRelationshipIdOrderByAssessedAtAsc(r.getId());
            if (history.isEmpty()) {
                continue; // not yet scored
            }
            items.add(toItem("RELATIONSHIP", null, r.getId(),
                    r.getFromCompany().getName() + " → " + r.getToCompany().getName(), history, affected));
        }

        return items.stream()
                .filter(i -> minSeverity == null || severityRank(i.severity()) >= severityRank(minSeverity))
                .sorted(Comparator.comparingInt((RiskItem i) -> severityRank(i.severity())).reversed()
                        .thenComparing(i -> i.score() == null ? -1 : i.score(), Comparator.reverseOrder()))
                .toList();
    }

    /** AC 4.9: portfolio weight exposed per holding sector and per supplier country, with a 35% warning. */
    @Transactional(readOnly = true)
    public Concentration concentration(Long portfolioId, Long userId) {
        AssembledGraph graph = graphAssemblyService.assemble(portfolioId, userId, false);
        Map<String, BigDecimal> bySector = new LinkedHashMap<>();
        BigDecimal holdingWeightTotal = BigDecimal.ZERO;
        for (NodeModel n : graph.nodesByCompanyId().values()) {
            if (!n.isHolding() || n.holding.getWeightPercent() == null) {
                continue;
            }
            String sector = n.company.getSector() == null ? "Unknown" : n.company.getSector();
            bySector.merge(sector, n.holding.getWeightPercent(), BigDecimal::add);
            holdingWeightTotal = holdingWeightTotal.add(n.holding.getWeightPercent());
        }
        Map<String, BigDecimal> byCountry = new LinkedHashMap<>();
        BigDecimal supplierWeightTotal = BigDecimal.ZERO;
        for (NodeModel n : graph.suppliers()) {
            String country = n.company.getCountry() == null ? "Unknown" : n.company.getCountry();
            byCountry.merge(country, n.dependantWeight, BigDecimal::add);
            supplierWeightTotal = supplierWeightTotal.add(n.dependantWeight);
        }
        return new Concentration(slices(bySector, holdingWeightTotal), slices(byCountry, supplierWeightTotal),
                RiskScoringService.GEO_CONCENTRATION_WARN);
    }

    // ---- helpers -----------------------------------------------------------------

    private RiskItem toItem(String kind, Long holdingId, Long relationshipId, String title,
                            List<RiskAssessment> history, List<AffectedHolding> affected) {
        RiskAssessment latest = history.isEmpty() ? null : history.get(history.size() - 1);
        RiskAssessment previous = history.size() >= 2 ? history.get(history.size() - 2) : null;
        Integer score = latest == null ? null : latest.getScore();
        RiskSeverity severity = latest == null ? RiskSeverity.UNKNOWN : latest.getSeverity();
        Integer prevScore = previous == null ? null : previous.getScore();
        BigDecimal exposed = affected.stream().map(AffectedHolding::weightPercent)
                .filter(w -> w != null).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new RiskItem(kind, holdingId, relationshipId, title, score, severity,
                latest == null ? List.of() : riskScoringService.fromJson(latest.getFactorsJson()),
                prevScore, trend(prevScore, score, previous == null),
                latest == null ? null : latest.getChangeReason(),
                latest == null ? null : latest.getAssessedAt(),
                affected, exposed);
    }

    /** AC 6.4: rising, falling, or new compared with the previous assessment. */
    private static String trend(Integer prev, Integer now, boolean first) {
        if (now == null) {
            return "UNKNOWN";
        }
        if (first || prev == null) {
            return "NEW";
        }
        if (now > prev) {
            return "RISING";
        }
        if (now < prev) {
            return "FALLING";
        }
        return "STABLE";
    }

    private static List<AffectedHolding> affectedHoldings(NodeModel supplier, AssembledGraph graph,
                                                         Map<Long, NodeModel> holdingsByCompany) {
        if (supplier == null) {
            return List.of();
        }
        List<AffectedHolding> out = new ArrayList<>();
        for (Long companyId : supplier.dependantHoldingCompanyIds) {
            NodeModel h = holdingsByCompany.get(companyId);
            if (h != null) {
                out.add(new AffectedHolding(h.holding.getId(), h.company.getTicker(), h.company.getName(),
                        h.holding.getWeightPercent()));
            }
        }
        return out;
    }

    private static List<Slice> slices(Map<String, BigDecimal> byKey, BigDecimal total) {
        List<Slice> out = new ArrayList<>();
        for (Map.Entry<String, BigDecimal> e : byKey.entrySet()) {
            double share = total.signum() == 0 ? 0
                    : e.getValue().divide(total, 4, RoundingMode.HALF_UP).doubleValue();
            boolean warn = share >= RiskScoringService.GEO_CONCENTRATION_WARN && !"Unknown".equals(e.getKey());
            out.add(new Slice(e.getKey(), e.getValue(), share, warn));
        }
        out.sort(Comparator.comparing(Slice::share).reversed());
        return out;
    }

    public static int severityRank(RiskSeverity s) {
        return switch (s) {
            case CRITICAL -> 4;
            case ELEVATED -> 3;
            case MODERATE -> 2;
            case LOW -> 1;
            case UNKNOWN -> 0;
        };
    }
}
