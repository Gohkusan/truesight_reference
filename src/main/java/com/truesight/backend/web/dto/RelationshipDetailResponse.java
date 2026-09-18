package com.truesight.backend.web.dto;

import com.truesight.backend.domain.Criticality;
import com.truesight.backend.domain.ReviewStatus;
import com.truesight.backend.domain.SourceType;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * AC 4.3 / 5.1 / 5.2 / 5.3 / 8.3 in one payload: type and direction relative to the
 * selected company, criticality, dependency %, the single confidence score with its
 * factor list, every source with its verbatim excerpt, last-verified date and the
 * no-longer-disclosed flag, the user's review, and the risk history.
 */
public record RelationshipDetailResponse(
        Long id,
        Long fromCompanyId,
        String fromCompanyName,
        Long toCompanyId,
        String toCompanyName,
        Criticality criticality,
        boolean singleSource,
        Integer dependencyPercent,
        String disruptionReason,
        int tier,
        Integer confidenceScore,
        String confidenceBand,
        List<String> confidenceFactors,
        boolean sourcesConflict,
        boolean noLongerDisclosed,
        LocalDate lastVerified,
        ReviewStatus reviewStatus,
        String reviewNote,
        Instant reviewedAt,
        List<EvidenceDto> evidence,
        List<RiskPoint> riskHistory
) {

    public record EvidenceDto(Long id, SourceType sourceType, LocalDate sourceDate, String sourceUrl,
                              String accessionNumber, String excerpt) {
    }

    /** AC 8.3: one point per real assessment; never interpolated or back-filled. */
    public record RiskPoint(Instant assessedAt, Integer score, String severity, List<String> factors, String changeReason) {
    }
}
