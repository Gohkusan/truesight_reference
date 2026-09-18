package com.truesight.backend.web.dto;

import com.truesight.backend.domain.RiskSeverity;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Epic 6 payloads. Grouped in one file because they are only ever used together. */
public final class RiskResponses {

    private RiskResponses() {
    }

    /** AC 3.1 composite card: 0-100 with band, plus the coverage denominator it rests on. */
    public record Summary(
            Integer compositeScore,
            RiskSeverity band,
            int scoredHoldings,
            int unknownHoldings,
            int totalHoldings,
            Instant lastAnalysedAt,
            String howComputed
    ) {
    }

    /** One risk row. Either a holding (relationshipId null) or a relationship (holdingId null). */
    public record RiskItem(
            String kind,                 // "HOLDING" | "RELATIONSHIP"
            Long holdingId,
            Long relationshipId,
            String title,                // "NVIDIA" or "TSMC → NVIDIA"
            Integer score,
            RiskSeverity severity,
            List<String> factors,
            Integer previousScore,       // AC 6.4
            String trend,                // NEW | RISING | FALLING | STABLE | UNKNOWN
            String changeReason,
            Instant assessedAt,
            /** AC 6.2: affected holdings with weight, and total weight exposed. */
            List<AffectedHolding> affectedHoldings,
            BigDecimal totalWeightExposed
    ) {
    }

    public record AffectedHolding(Long holdingId, String ticker, String name, BigDecimal weightPercent) {
    }

    /** AC 4.9 concentration view: exposure by sector and by supplier country, with a 35% warning. */
    public record Concentration(List<Slice> bySector, List<Slice> bySupplierCountry, double warnThreshold) {
    }

    public record Slice(String key, BigDecimal weightPercent, double share, boolean warning) {
    }
}
