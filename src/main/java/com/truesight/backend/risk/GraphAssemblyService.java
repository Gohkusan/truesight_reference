package com.truesight.backend.risk;

import com.truesight.backend.domain.Company;
import com.truesight.backend.domain.Criticality;
import com.truesight.backend.domain.Evidence;
import com.truesight.backend.domain.Holding;
import com.truesight.backend.domain.Relationship;
import com.truesight.backend.domain.RelationshipReview;
import com.truesight.backend.domain.ReviewStatus;
import com.truesight.backend.domain.RiskAssessment;
import com.truesight.backend.repository.HoldingRepository;
import com.truesight.backend.repository.RelationshipRepository;
import com.truesight.backend.repository.RelationshipReviewRepository;
import com.truesight.backend.repository.RiskAssessmentRepository;
import com.truesight.backend.web.dto.GraphResponse;
import com.truesight.backend.web.dto.GraphResponse.GraphEdge;
import com.truesight.backend.web.dto.GraphResponse.GraphNode;
import com.truesight.backend.web.dto.GraphResponse.NodeKind;
import com.truesight.backend.web.dto.GraphResponse.SharedSupplier;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds the supply-chain graph for one portfolio from persisted Relationship rows
 * (Epic 4). Two consumers: the /graph endpoint (via {@link #toResponse}) and
 * RiskScoringService, which needs the same structure — in particular the
 * dependant-holding count per supplier — to compute shared-supplier factors. Building
 * it in one place is what guarantees the node size, the shared-supplier table, and the
 * risk factor all report the same number (AC 4.4/4.5: "the two must never disagree").
 *
 * <p>Walk: from each holding, follow SUPPLIER edges UPSTREAM (edge.to == current, so
 * edge.from is a supplier) for two levels: tier 1, then tier 2 (AC 4.8's cap). A
 * company reachable at several depths takes the shallowest (AC 4.1: layout by tier
 * needs one tier per node). Customers are one hop DOWNSTREAM of a holding (edge.from
 * == holding) and are not walked further. Self-loops are dropped at write time
 * (GeminiExtractionService); cycles are harmless here because the BFS tracks visited
 * nodes (AC 4.1: "cycles... render without error").
 *
 * <p>Per-user filtering: a RelationshipReview of REJECTED hides the edge (AC 5.4)
 * unless the caller asks for rejected edges ("show rejected" toggle). A relationship
 * flagged noLongerDisclosed stays visible (with its flag) but is excluded from risk
 * scoring unless CONFIRMED (AC 5.3) — that exclusion lives in RiskScoringService,
 * which reads the flag from the edge model built here.
 */
@Service
public class GraphAssemblyService {

    private final HoldingRepository holdingRepository;
    private final RelationshipRepository relationshipRepository;
    private final RelationshipReviewRepository reviewRepository;
    private final RiskAssessmentRepository riskAssessmentRepository;

    public GraphAssemblyService(HoldingRepository holdingRepository,
                                RelationshipRepository relationshipRepository,
                                RelationshipReviewRepository reviewRepository,
                                RiskAssessmentRepository riskAssessmentRepository) {
        this.holdingRepository = holdingRepository;
        this.relationshipRepository = relationshipRepository;
        this.reviewRepository = reviewRepository;
        this.riskAssessmentRepository = riskAssessmentRepository;
    }

    // ---- internal model, shared with RiskScoringService ----------------------------

    public static final class NodeModel {
        public final Company company;
        public final Holding holding; // null for non-holdings
        public int tier;               // 0 holding, 1, 2, or -1 customer
        public final Set<Long> dependantHoldingCompanyIds = new LinkedHashSet<>();
        public BigDecimal dependantWeight = BigDecimal.ZERO;
        public final List<String> alsoSupplies = new ArrayList<>();
        public boolean hasAnyEdge;

        NodeModel(Company company, Holding holding, int tier) {
            this.company = company;
            this.holding = holding;
            this.tier = tier;
        }

        public boolean isHolding() {
            return holding != null;
        }
    }

    public static final class EdgeModel {
        public final Relationship relationship;
        public final ReviewStatus reviewStatus;
        public final int tier; // tier of the supplier end, relative to this portfolio

        EdgeModel(Relationship relationship, ReviewStatus reviewStatus, int tier) {
            this.relationship = relationship;
            this.reviewStatus = reviewStatus;
            this.tier = tier;
        }

        /** AC 5.3: excluded from scoring unless the user has confirmed it. */
        public boolean countsTowardRisk() {
            if (reviewStatus == ReviewStatus.REJECTED) {
                return false;
            }
            return !relationship.isNoLongerDisclosed() || reviewStatus == ReviewStatus.CONFIRMED;
        }
    }

    public record AssembledGraph(
            Map<Long, NodeModel> nodesByCompanyId,
            List<EdgeModel> edges,
            int rejectedEdgesHidden
    ) {
        public List<NodeModel> suppliers() {
            return nodesByCompanyId.values().stream().filter(n -> n.tier > 0).toList();
        }
    }

    @Transactional(readOnly = true)
    public AssembledGraph assemble(Long portfolioId, Long userId, boolean includeRejected) {
        List<Holding> holdings = holdingRepository.findActiveByPortfolioId(portfolioId);
        Map<Long, ReviewStatus> reviews = new HashMap<>();
        for (RelationshipReview r : reviewRepository.findByUserId(userId)) {
            reviews.put(r.getRelationship().getId(), r.getStatus());
        }

        Map<Long, NodeModel> nodes = new LinkedHashMap<>();
        for (Holding h : holdings) {
            nodes.put(h.getCompany().getId(), new NodeModel(h.getCompany(), h, 0));
        }
        Set<Long> holdingIds = new HashSet<>(nodes.keySet());

        List<EdgeModel> edges = new ArrayList<>();
        Set<Long> seenEdgeIds = new HashSet<>();
        int rejectedHidden = 0;

        // Upstream BFS, two levels. Frontier holds (companyId, tierOfThatNode).
        Deque<long[]> frontier = new ArrayDeque<>();
        for (Long id : holdingIds) {
            frontier.add(new long[]{id, 0});
        }
        while (!frontier.isEmpty()) {
            long[] cur = frontier.poll();
            long companyId = cur[0];
            int tier = (int) cur[1];
            if (tier >= 2) {
                continue; // AC 4.8: expansion capped at tier 2
            }
            for (Relationship r : relationshipRepository.findByToCompanyId(companyId)) {
                ReviewStatus status = reviews.getOrDefault(r.getId(), ReviewStatus.PENDING);
                if (status == ReviewStatus.REJECTED && !includeRejected) {
                    rejectedHidden++;
                    continue;
                }
                Company supplier = r.getFromCompany();
                int supplierTier = tier + 1;
                NodeModel node = nodes.get(supplier.getId());
                if (node == null) {
                    node = new NodeModel(supplier, null, supplierTier);
                    nodes.put(supplier.getId(), node);
                    frontier.add(new long[]{supplier.getId(), supplierTier});
                } else if (!node.isHolding() && supplierTier < node.tier) {
                    node.tier = supplierTier; // shallower path found
                } else if (node.isHolding()) {
                    // AC 4.1: a holding that is also a supplier stays a holding, badged.
                    String buyer = nodes.get(companyId).company.getName();
                    if (!node.alsoSupplies.contains(buyer)) {
                        node.alsoSupplies.add(buyer);
                    }
                }
                if (seenEdgeIds.add(r.getId())) {
                    edges.add(new EdgeModel(r, status, supplierTier));
                    node.hasAnyEdge = true;
                    nodes.get(companyId).hasAnyEdge = true;
                }
            }
        }

        // Customers: one hop downstream of a holding, never walked further.
        for (Long holdingCompanyId : holdingIds) {
            for (Relationship r : relationshipRepository.findByFromCompanyId(holdingCompanyId)) {
                ReviewStatus status = reviews.getOrDefault(r.getId(), ReviewStatus.PENDING);
                if (status == ReviewStatus.REJECTED && !includeRejected) {
                    rejectedHidden++;
                    continue;
                }
                Company customer = r.getToCompany();
                if (!nodes.containsKey(customer.getId())) {
                    nodes.put(customer.getId(), new NodeModel(customer, null, -1));
                }
                if (seenEdgeIds.add(r.getId())) {
                    edges.add(new EdgeModel(r, status, 0));
                    nodes.get(customer.getId()).hasAnyEdge = true;
                    nodes.get(holdingCompanyId).hasAnyEdge = true;
                }
            }
        }

        computeDependants(nodes, edges, holdingIds);
        return new AssembledGraph(nodes, edges, rejectedHidden);
    }

    /**
     * The number behind node size, the shared-supplier table, and the shared-supplier
     * risk factor: for each supplier, which of MY holdings ultimately depend on it.
     * Walks downstream from the supplier along counted edges until it reaches
     * holdings. A tier-2 supplier feeding two tier-1 suppliers that both feed the
     * same holding counts that holding once (it's a set).
     */
    private void computeDependants(Map<Long, NodeModel> nodes, List<EdgeModel> edges, Set<Long> holdingIds) {
        Map<Long, List<Long>> downstream = new HashMap<>();
        for (EdgeModel e : edges) {
            if (!e.countsTowardRisk()) {
                continue;
            }
            downstream.computeIfAbsent(e.relationship.getFromCompany().getId(), k -> new ArrayList<>())
                    .add(e.relationship.getToCompany().getId());
        }
        for (NodeModel supplier : nodes.values()) {
            if (supplier.tier <= 0) {
                continue;
            }
            Set<Long> visited = new HashSet<>();
            Deque<Long> stack = new ArrayDeque<>();
            stack.push(supplier.company.getId());
            while (!stack.isEmpty()) {
                Long cur = stack.pop();
                if (!visited.add(cur)) {
                    continue;
                }
                for (Long next : downstream.getOrDefault(cur, List.of())) {
                    if (holdingIds.contains(next)) {
                        supplier.dependantHoldingCompanyIds.add(next);
                    } else {
                        stack.push(next);
                    }
                }
            }
            BigDecimal weight = BigDecimal.ZERO;
            for (Long hid : supplier.dependantHoldingCompanyIds) {
                BigDecimal w = nodes.get(hid).holding.getWeightPercent();
                if (w != null) {
                    weight = weight.add(w);
                }
            }
            supplier.dependantWeight = weight;
        }
    }

    // ---- response mapping ---------------------------------------------------------

    @Transactional(readOnly = true)
    public GraphResponse toResponse(AssembledGraph graph) {
        List<GraphNode> nodeDtos = new ArrayList<>();
        for (NodeModel n : graph.nodesByCompanyId().values()) {
            Company c = n.company;
            NodeKind kind = n.isHolding() ? NodeKind.HOLDING : (n.tier < 0 ? NodeKind.CUSTOMER : NodeKind.SUPPLIER);
            nodeDtos.add(new GraphNode(
                    c.getId(), c.getName(), c.getTicker(), kind, n.tier,
                    n.isHolding() ? n.holding.getWeightPercent() : null,
                    n.dependantHoldingCompanyIds.size(),
                    n.dependantWeight,
                    c.getSector(), c.getCountry(), c.isPrivate(), c.isHasNoSecFilings(),
                    n.isHolding() && n.holding.isNewlyAdded(),
                    n.isHolding() ? n.holding.getCoverageStatus() : null,
                    List.copyOf(n.alsoSupplies),
                    n.isHolding() && !n.hasAnyEdge
            ));
        }

        List<GraphEdge> edgeDtos = new ArrayList<>();
        for (EdgeModel e : graph.edges()) {
            Relationship r = e.relationship;
            LocalDate lastVerified = r.getEvidence().stream()
                    .map(Evidence::getSourceDate).filter(d -> d != null)
                    .max(Comparator.naturalOrder()).orElse(null);
            RiskAssessment latest = riskAssessmentRepository
                    .findTopByRelationshipIdOrderByAssessedAtDesc(r.getId()).orElse(null);
            Integer conf = r.getConfidenceScore();
            edgeDtos.add(new GraphEdge(
                    r.getId(), r.getFromCompany().getId(), r.getToCompany().getId(),
                    r.getCriticality(), r.getCriticality() == Criticality.SINGLE_SOURCE,
                    r.getDependencyPercent(), conf,
                    conf == null ? null : ConfidenceScoringService.bandOf(conf).name(),
                    e.reviewStatus, r.isNoLongerDisclosed(), r.isSourcesConflict(),
                    r.getEvidence().size(), lastVerified,
                    latest == null ? null : latest.getScore(),
                    latest == null ? null : latest.getSeverity().name()
            ));
        }

        List<SharedSupplier> shared = graph.suppliers().stream()
                .filter(n -> n.dependantHoldingCompanyIds.size() >= 2)
                .sorted(Comparator
                        .comparingInt((NodeModel n) -> n.dependantHoldingCompanyIds.size()).reversed()
                        .thenComparing((NodeModel n) -> n.dependantWeight, Comparator.reverseOrder()))
                .map(n -> new SharedSupplier(
                        n.company.getId(), n.company.getName(),
                        n.dependantHoldingCompanyIds.size(), n.dependantWeight,
                        n.dependantHoldingCompanyIds.stream()
                                .map(id -> graph.nodesByCompanyId().get(id).company.getTicker()).toList()))
                .toList();

        return new GraphResponse(nodeDtos, edgeDtos, shared, graph.rejectedEdgesHidden(), Instant.now());
    }
}
