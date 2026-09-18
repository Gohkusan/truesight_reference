package com.truesight.backend.web.dto;

import com.truesight.backend.domain.CoverageStatus;
import com.truesight.backend.domain.Holding;
import java.math.BigDecimal;
import java.time.Instant;

/** AC 2.2's required columns: ticker, name, weight %, coverage status, last analysed time. */
public record HoldingResponse(
        Long id,
        String ticker,
        String companyName,
        BigDecimal weightPercent,
        BigDecimal shares,
        BigDecimal marketValue,
        CoverageStatus coverageStatus,
        String failureReason,
        Instant lastAnalysedAt,
        boolean newlyAdded
) {
    public static HoldingResponse from(Holding h) {
        return new HoldingResponse(
                h.getId(), h.getCompany().getTicker(), h.getCompany().getName(),
                h.getWeightPercent(), h.getShares(), h.getMarketValue(),
                h.getCoverageStatus(), h.getFailureReason(), h.getLastAnalysedAt(), h.isNewlyAdded()
        );
    }
}
