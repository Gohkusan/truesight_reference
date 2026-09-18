package com.truesight.backend.risk;

import com.truesight.backend.common.ValidationException;
import com.truesight.backend.domain.Criticality;
import com.truesight.backend.domain.Relationship;
import com.truesight.backend.risk.GraphAssemblyService.AssembledGraph;
import com.truesight.backend.risk.GraphAssemblyService.EdgeModel;
import com.truesight.backend.risk.GraphAssemblyService.NodeModel;
import com.truesight.backend.web.dto.SimulationDtos.AffectedHoldingResult;
import com.truesight.backend.web.dto.SimulationDtos.AffectedNode;
import com.truesight.backend.web.dto.SimulationDtos.ScenarioRequest;
import com.truesight.backend.web.dto.SimulationDtos.ScenarioResult;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Epic 12: propagate a disruption downstream from an epicentre and report which
 * holdings it reaches, with the path and the weight exposed.
 *
 * <p>The model is deliberately simple and fully traceable (AC 12.2: "every number in
 * the explanation is traceable to an edge or holding attribute"):
 * <ul>
 *   <li>The epicentre loses {@code severity}% of capacity.</li>
 *   <li>Each SUPPLIER edge passes impact downstream scaled by the edge's criticality:
 *       SINGLE_SOURCE 1.0 (no alternative), DUAL_SOURCE 0.5, DIVERSIFIED 0.25 — and
 *       by the disclosed dependency %, when the filing stated one.</li>
 *   <li>A node hit by several paths takes the MAX impact, not the sum: two half-
 *       blocked routes to the same buyer do not add up to a full block.</li>
 *   <li>Duration does not change the fraction, only the framing: it is reported and
 *       used to label the scenario, because we have no verified inventory-buffer data
 *       to convert days into stockouts (the old prototype's lead-time/buffer numbers
 *       were hardcoded and are exactly what the spec's "no fabricated data" rule cut).</li>
 * </ul>
 * A holding's "weight at impact" = its portfolio weight × its impact fraction. That is
 * the one derived number, and it is shown next to both of its inputs.
 *
 * <p>Nothing is persisted: a simulation is a question about the current graph, not
 * a fact about the world. Compare (12.3) runs two scenarios in one request.
 */
@Service
public class SimulationService {

    public static final String DISCLAIMER =
            "Scenario output is a research aid computed from disclosed relationships and stated criticality. "
            + "It is not a forecast and not investment advice. Impact fractions are model assumptions "
            + "(single-source 1.0, dual-source 0.5, diversified 0.25), not measured outcomes.";

    private final GraphAssemblyService graphAssemblyService;

    public SimulationService(GraphAssemblyService graphAssemblyService) {
        this.graphAssemblyService = graphAssemblyService;
    }

    @Transactional(readOnly = true)
    public ScenarioResult run(Long portfolioId, Long userId, ScenarioRequest req) {
        AssembledGraph graph = graphAssemblyService.assemble(portfolioId, userId, false);

        Set<Long> epicentre = new LinkedHashSet<>();
        if (req.epicentreCompanyIds() != null && !req.epicentreCompanyIds().isEmpty()) {
            for (Long id : req.epicentreCompanyIds()) {
                if (!graph.nodesByCompanyId().containsKey(id)) {
                    throw new ValidationException("Company " + id + " is not in this portfolio's graph");
                }
                epicentre.add(id);
            }
        } else if (req.epicentreSector() != null && !req.epicentreSector().isBlank()) {
            for (NodeModel n : graph.nodesByCompanyId().values()) {
                if (req.epicentreSector().equalsIgnoreCase(n.company.getSector())) {
                    epicentre.add(n.company.getId());
                }
            }
            if (epicentre.isEmpty()) {
                throw new ValidationException("No company in this graph has sector \"" + req.epicentreSector() + "\"");
            }
        } else {
            throw new ValidationException("Provide epicentreCompanyIds or epicentreSector");
        }

        // Downstream adjacency over counted edges.
        Map<Long, List<EdgeModel>> downstream = new HashMap<>();
        for (EdgeModel e : graph.edges()) {
            if (e.countsTowardRisk()) {
                downstream.computeIfAbsent(e.relationship.getFromCompany().getId(), k -> new ArrayList<>()).add(e);
            }
        }

        double base = req.severityPercent() / 100.0;
        Map<Long, Double> impact = new HashMap<>();
        Map<Long, Integer> hops = new HashMap<>();
        Map<Long, List<String>> path = new HashMap<>();
        List<String> explanation = new ArrayList<>();

        Deque<Long> queue = new ArrayDeque<>();
        for (Long id : epicentre) {
            impact.put(id, base);
            hops.put(id, 0);
            path.put(id, List.of(name(graph, id)));
            queue.add(id);
            explanation.add(String.format("%s: epicentre, %d%% capacity lost", name(graph, id), req.severityPercent()));
        }

        while (!queue.isEmpty()) {
            Long cur = queue.poll();
            double curImpact = impact.get(cur);
            for (EdgeModel e : downstream.getOrDefault(cur, List.of())) {
                Relationship r = e.relationship;
                Long next = r.getToCompany().getId();
                double factor = transmission(r);
                double propagated = curImpact * factor;
                if (propagated < 0.01) {
                    continue;
                }
                Double existing = impact.get(next);
                if (existing == null || propagated > existing + 1e-9) {
                    impact.put(next, propagated);
                    hops.put(next, hops.get(cur) + 1);
                    List<String> p = new ArrayList<>(path.get(cur));
                    p.add(name(graph, next));
                    path.put(next, p);
                    explanation.add(String.format("%s → %s: %s%s, transmission %.2f → impact %.0f%%",
                            name(graph, cur), name(graph, next), r.getCriticality().name().toLowerCase().replace('_', '-'),
                            r.getDependencyPercent() == null ? "" : " (" + r.getDependencyPercent() + "% dependency)",
                            factor, propagated * 100));
                    queue.add(next);
                }
            }
        }

        List<AffectedNode> nodes = new ArrayList<>();
        List<AffectedHoldingResult> holdings = new ArrayList<>();
        BigDecimal exposed = BigDecimal.ZERO;
        for (Map.Entry<Long, Double> en : impact.entrySet()) {
            NodeModel n = graph.nodesByCompanyId().get(en.getKey());
            nodes.add(new AffectedNode(n.company.getId(), n.company.getName(), hops.get(en.getKey()), round(en.getValue())));
            if (n.isHolding()) {
                BigDecimal w = n.holding.getWeightPercent();
                BigDecimal atImpact = w == null ? null : w.multiply(BigDecimal.valueOf(en.getValue())).setScale(2, RoundingMode.HALF_UP);
                holdings.add(new AffectedHoldingResult(n.holding.getId(), n.company.getTicker(), n.company.getName(),
                        w, round(en.getValue()), atImpact, path.get(en.getKey())));
                if (w != null) {
                    exposed = exposed.add(w);
                }
            }
        }
        nodes.sort(Comparator.comparingInt(AffectedNode::hops).thenComparing(AffectedNode::name));
        holdings.sort(Comparator.comparing((AffectedHoldingResult h) -> h.weightAtImpact() == null ? BigDecimal.ZERO : h.weightAtImpact()).reversed());

        List<String> epicentreNames = epicentre.stream().map(id -> name(graph, id)).toList();
        String label = req.label() != null && !req.label().isBlank() ? req.label()
                : String.join(", ", epicentreNames) + " −" + req.severityPercent() + "% for " + req.durationDays() + " days";
        return new ScenarioResult(label, new ArrayList<>(epicentre), epicentreNames, req.severityPercent(),
                req.durationDays(), nodes, holdings, exposed, explanation, DISCLAIMER);
    }

    static double transmission(Relationship r) {
        double byCriticality = switch (r.getCriticality()) {
            case SINGLE_SOURCE -> 1.0;
            case DUAL_SOURCE -> 0.5;
            case DIVERSIFIED -> 0.25;
        };
        if (r.getDependencyPercent() != null) {
            // A disclosed dependency share refines the criticality prior; take the
            // larger so a "single-source, 30% of volume" still transmits fully — the
            // filing said there is no alternative for that 30%.
            return Math.max(byCriticality, r.getDependencyPercent() / 100.0 * (r.getCriticality() == Criticality.SINGLE_SOURCE ? 1.0 : 0.5));
        }
        return byCriticality;
    }

    private static String name(AssembledGraph g, Long id) {
        return g.nodesByCompanyId().get(id).company.getName();
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
