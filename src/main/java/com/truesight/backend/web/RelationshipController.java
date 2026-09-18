package com.truesight.backend.web;

import com.truesight.backend.domain.Criticality;
import com.truesight.backend.domain.Evidence;
import com.truesight.backend.domain.Relationship;
import com.truesight.backend.domain.RelationshipReview;
import com.truesight.backend.repository.RiskAssessmentRepository;
import com.truesight.backend.risk.ConfidenceScoringService;
import com.truesight.backend.risk.GraphAssemblyService.EdgeModel;
import com.truesight.backend.risk.RiskScoringService;
import com.truesight.backend.security.CurrentUser;
import com.truesight.backend.service.PortfolioService;
import com.truesight.backend.service.RelationshipReviewService;
import com.truesight.backend.web.dto.RelationshipDetailResponse;
import com.truesight.backend.web.dto.RelationshipDetailResponse.EvidenceDto;
import com.truesight.backend.web.dto.RelationshipDetailResponse.RiskPoint;
import com.truesight.backend.web.dto.ReviewRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Epic 5: evidence, confidence, staleness, and the user's confirm/reject override. */
@RestController
@RequestMapping("/api/portfolios/{portfolioId}/relationships")
@Tag(name = "Relationships", description = "Evidence excerpts, confidence, and confirm/reject (Epic 5).")
public class RelationshipController {

    private final PortfolioService portfolioService;
    private final RelationshipReviewService reviewService;
    private final ConfidenceScoringService confidenceScoringService;
    private final RiskScoringService riskScoringService;
    private final RiskAssessmentRepository riskAssessmentRepository;
    private final CurrentUser currentUser;

    public RelationshipController(PortfolioService portfolioService,
                                  RelationshipReviewService reviewService,
                                  ConfidenceScoringService confidenceScoringService,
                                  RiskScoringService riskScoringService,
                                  RiskAssessmentRepository riskAssessmentRepository,
                                  CurrentUser currentUser) {
        this.portfolioService = portfolioService;
        this.reviewService = reviewService;
        this.confidenceScoringService = confidenceScoringService;
        this.riskScoringService = riskScoringService;
        this.riskAssessmentRepository = riskAssessmentRepository;
        this.currentUser = currentUser;
    }

    @GetMapping("/{relationshipId}")
    @Operation(summary = "Relationship detail with every source excerpt, confidence factors, and risk history")
    public RelationshipDetailResponse detail(@PathVariable Long portfolioId, @PathVariable Long relationshipId) {
        portfolioService.getOwned(portfolioId, currentUser.id());
        EdgeModel edge = reviewService.getOwnedEdge(portfolioId, relationshipId, currentUser.id());
        return toDetail(edge);
    }

    @PutMapping("/{relationshipId}/review")
    @Operation(summary = "Confirm or reject a relationship (AC 5.4)",
            description = "Reversible: send PENDING to clear a previous judgement. Triggers a rescore.")
    public RelationshipDetailResponse review(@PathVariable Long portfolioId, @PathVariable Long relationshipId,
                                             @Valid @RequestBody ReviewRequest request) {
        portfolioService.getOwned(portfolioId, currentUser.id());
        reviewService.review(portfolioId, relationshipId, currentUser.id(), request.status(), request.note());
        return toDetail(reviewService.getOwnedEdge(portfolioId, relationshipId, currentUser.id()));
    }

    private RelationshipDetailResponse toDetail(EdgeModel edge) {
        Relationship r = edge.relationship;
        ConfidenceScoringService.Result conf = confidenceScoringService.score(r, LocalDate.now());
        Optional<RelationshipReview> review = reviewService.findReview(currentUser.id(), r.getId());

        List<EvidenceDto> evidence = r.getEvidence().stream()
                .sorted(Comparator.comparing(Evidence::getSourceDate, Comparator.nullsLast(Comparator.reverseOrder())))
                .map(e -> new EvidenceDto(e.getId(), e.getSourceType(), e.getSourceDate(), e.getSourceUrl(),
                        e.getAccessionNumber(), e.getExcerpt()))
                .toList();
        LocalDate lastVerified = evidence.stream().map(EvidenceDto::sourceDate).filter(d -> d != null)
                .max(Comparator.naturalOrder()).orElse(null);

        List<RiskPoint> history = riskAssessmentRepository.findByRelationshipIdOrderByAssessedAtAsc(r.getId()).stream()
                .map(a -> new RiskPoint(a.getAssessedAt(), a.getScore(), a.getSeverity().name(),
                        riskScoringService.fromJson(a.getFactorsJson()), a.getChangeReason()))
                .toList();

        return new RelationshipDetailResponse(
                r.getId(),
                r.getFromCompany().getId(), r.getFromCompany().getName(),
                r.getToCompany().getId(), r.getToCompany().getName(),
                r.getCriticality(), r.getCriticality() == Criticality.SINGLE_SOURCE,
                r.getDependencyPercent(), r.getDisruptionReason(), edge.tier,
                conf.score(), conf.band().name(), conf.factors(),
                r.isSourcesConflict(), r.isNoLongerDisclosed(), lastVerified,
                edge.reviewStatus,
                review.map(RelationshipReview::getNote).orElse(null),
                review.map(RelationshipReview::getReviewedAt).orElse(null),
                evidence, history
        );
    }
}
