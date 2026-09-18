package com.truesight.backend.web.dto;

import com.truesight.backend.domain.CoverageStatus;
import com.truesight.backend.domain.Criticality;
import com.truesight.backend.domain.ReviewStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * The network graph for one portfolio (Epic 4). Assembled by GraphAssemblyService.
 *
 * <p>Two numbers here are deliberately computed ONCE and shared: GraphNode.dependantHoldingCount
 * is both the node-size input (AC 4.4) and the sort key of the shared-supplier table
 * (AC 4.5) — the spec says "the two must never disagree", and the surest way to make
 * that true is for the table to be built from the same node objects.
 */
public record GraphResponse(
        List<GraphNode> nodes,
        List<GraphEdge> edges,
        List<SharedSupplier> sharedSuppliers,
        int rejectedEdgesHidden,
        Instant generatedAt
) {

    public enum NodeKind { HOLDING, SUPPLIER, CUSTOMER }

    public record GraphNode(
            Long id,
            String name,
            String ticker,
            NodeKind kind,
            /** 0 = holding, 1 = tier-1 supplier, 2 = tier-2 supplier, -1 = customer. */
            int tier,
            BigDecimal weightPercent,
            /** Number of the user's holdings that depend on this company (transitively). */
            int dependantHoldingCount,
            /** Combined portfolio weight of those holdings. */
            BigDecimal dependantWeightPercent,
            String sector,
            String country,
            boolean isPrivate,
            boolean hasNoSecFilings,
            boolean newlyAdded,
            CoverageStatus coverageStatus,
            /** AC 4.1: a holding that is also a supplier renders as a holding with an "also supplies X" badge. */
            List<String> alsoSupplies,
            /** AC 4.1: "a company with no discovered relationships still appears, with a note on click." */
            boolean noRelationshipsFound
    ) {
    }

    public record GraphEdge(
            Long id,
            Long fromCompanyId,
            Long toCompanyId,
            Criticality criticality,
            boolean singleSource,
            Integer dependencyPercent,
            Integer confidenceScore,
            String confidenceBand,
            ReviewStatus reviewStatus,
            boolean noLongerDisclosed,
            boolean sourcesConflict,
            int evidenceCount,
            LocalDate lastVerified,
            Integer riskScore,
            String riskSeverity
    ) {
    }

    public record SharedSupplier(
            Long companyId,
            String name,
            int holdingCount,
            BigDecimal combinedWeightPercent,
            List<String> holdingTickers
    ) {
    }
}
