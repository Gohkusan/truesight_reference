package com.truesight.backend.web.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.math.BigDecimal;
import java.util.List;

/** Epic 12: scenario simulation request/response. */
public final class SimulationDtos {

    private SimulationDtos() {
    }

    /**
     * AC 12.1: epicentre is one or more companies OR a whole sector; severity 20-100%;
     * duration 30-365 days. Exactly one of epicentreCompanyIds / epicentreSector is used.
     */
    public record ScenarioRequest(
            List<Long> epicentreCompanyIds,
            String epicentreSector,
            @Min(20) @Max(100) int severityPercent,
            @Min(30) @Max(365) int durationDays,
            String label
    ) {
    }

    public record ScenarioResult(
            String label,
            List<Long> epicentreCompanyIds,
            List<String> epicentreNames,
            int severityPercent,
            int durationDays,
            List<AffectedNode> affectedNodes,
            List<AffectedHoldingResult> affectedHoldings,
            BigDecimal totalWeightExposed,
            /** AC 12.2: every number is traceable to an edge or holding attribute — this explains how. */
            List<String> explanation,
            String disclaimer
    ) {
    }

    public record AffectedNode(Long companyId, String name, int hops, double impactFraction) {
    }

    public record AffectedHoldingResult(Long holdingId, String ticker, String name, BigDecimal weightPercent,
                                        double impactFraction, BigDecimal weightAtImpact, List<String> path) {
    }

    public record CompareRequest(ScenarioRequest a, ScenarioRequest b) {
    }

    public record CompareResult(ScenarioResult a, ScenarioResult b, BigDecimal exposureDifference, String moreSevere) {
    }
}
