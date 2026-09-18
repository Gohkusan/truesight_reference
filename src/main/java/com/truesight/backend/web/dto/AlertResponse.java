package com.truesight.backend.web.dto;

import com.truesight.backend.domain.Alert;
import com.truesight.backend.domain.AlertSeverity;
import com.truesight.backend.domain.AlertSource;
import com.truesight.backend.domain.AlertStatus;
import com.truesight.backend.domain.DismissReason;
import com.truesight.backend.web.dto.RiskResponses.AffectedHolding;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * AC 7.2: the affected supplier, my affected holdings (with weight), why it matters,
 * and every source. AC 7.1: eventAt is the event's own timestamp, detectedAt is when
 * TrueSight found it — both shown, never conflated.
 */
public record AlertResponse(
        Long id,
        String headline,
        String summary,
        AlertSeverity severity,
        AlertStatus status,
        DismissReason dismissReason,
        Long sourceCompanyId,
        String sourceCompanyName,
        String sourceCompanyTicker,
        boolean sourceIsHolding,
        List<AffectedHolding> affectedHoldings,
        BigDecimal totalWeightExposed,
        Instant eventAt,
        Instant detectedAt,
        List<Source> sources
) {

    public record Source(String title, String outlet, String url, Instant publishedAt) {
        public static Source from(AlertSource s) {
            return new Source(s.getTitle(), s.getOutlet(), s.getArticleUrl(), s.getPublishedAt());
        }
    }

    public static AlertResponse from(Alert a, boolean sourceIsHolding, List<AffectedHolding> affected) {
        BigDecimal exposed = affected.stream().map(AffectedHolding::weightPercent)
                .filter(w -> w != null).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new AlertResponse(
                a.getId(), a.getHeadline(), a.getSummary(), a.getSeverity(), a.getStatus(), a.getDismissReason(),
                a.getSourceCompany().getId(), a.getSourceCompany().getName(), a.getSourceCompany().getTicker(),
                sourceIsHolding, affected, exposed, a.getEventAt(), a.getCreatedAt(),
                a.getSources().stream().map(Source::from).toList()
        );
    }
}
