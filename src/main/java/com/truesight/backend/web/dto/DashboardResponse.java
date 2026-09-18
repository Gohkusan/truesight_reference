package com.truesight.backend.web.dto;

import com.truesight.backend.web.dto.RiskResponses.RiskItem;
import com.truesight.backend.web.dto.RiskResponses.Summary;
import java.time.Instant;
import java.util.List;

/**
 * AC 3.1: composite score card, top 5 risks, 5 most recent alerts, coverage n of N.
 * AC 3.3: each section carries its own timestamp. AC 3.4: since-your-last-visit counts.
 */
public record DashboardResponse(
        Long portfolioId,
        String portfolioName,
        Summary risk,
        List<RiskItem> topRisks,
        List<AlertResponse> recentAlerts,
        Coverage coverage,
        SinceLastVisit sinceLastVisit,
        Instant generatedAt
) {

    public record Coverage(long covered, long total, long pending, long failed, long noSecFilings, Instant lastAnalysedAt) {
    }

    public record SinceLastVisit(Instant previousLoginAt, long newAlerts, long changedRiskScores, long newRelationships) {
    }
}
