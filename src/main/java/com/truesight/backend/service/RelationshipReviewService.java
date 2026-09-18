package com.truesight.backend.service;

import com.truesight.backend.common.NotFoundException;
import com.truesight.backend.domain.Relationship;
import com.truesight.backend.domain.RelationshipReview;
import com.truesight.backend.domain.ReviewStatus;
import com.truesight.backend.domain.User;
import com.truesight.backend.repository.RelationshipReviewRepository;
import com.truesight.backend.repository.UserRepository;
import com.truesight.backend.risk.GraphAssemblyService;
import com.truesight.backend.risk.GraphAssemblyService.EdgeModel;
import com.truesight.backend.risk.RiskScoringService;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * AC 5.4: confirm/reject a relationship, with an optional note, timestamped, and
 * reversible. The judgement is per-user (see RelationshipReview's Javadoc), and a
 * change triggers a rescore because rejected edges leave the risk calculation and
 * confirmed noLongerDisclosed edges re-enter it (AC 5.3, 5.4).
 *
 * <p>Ownership: a relationship is visible to a user only through a portfolio they own
 * — it is "theirs" if it appears in that portfolio's assembled graph (rejected edges
 * included, so a user can un-reject). Anything else is 404, per AC 1.3.
 */
@Service
public class RelationshipReviewService {

    private final GraphAssemblyService graphAssemblyService;
    private final RelationshipReviewRepository reviewRepository;
    private final RiskScoringService riskScoringService;
    private final UserRepository userRepository;

    public RelationshipReviewService(GraphAssemblyService graphAssemblyService,
                                     RelationshipReviewRepository reviewRepository,
                                     RiskScoringService riskScoringService,
                                     UserRepository userRepository) {
        this.graphAssemblyService = graphAssemblyService;
        this.reviewRepository = reviewRepository;
        this.riskScoringService = riskScoringService;
        this.userRepository = userRepository;
    }

    /** The edge as it appears in THIS user's view of THIS portfolio, or 404. */
    @Transactional(readOnly = true)
    public EdgeModel getOwnedEdge(Long portfolioId, Long relationshipId, Long userId) {
        return graphAssemblyService.assemble(portfolioId, userId, true).edges().stream()
                .filter(e -> e.relationship.getId().equals(relationshipId))
                .findFirst()
                .orElseThrow(() -> new NotFoundException("Relationship not found"));
    }

    @Transactional
    public RelationshipReview review(Long portfolioId, Long relationshipId, Long userId, ReviewStatus status, String note) {
        Relationship relationship = getOwnedEdge(portfolioId, relationshipId, userId).relationship;
        User user = userRepository.getReferenceById(userId);

        RelationshipReview review = reviewRepository.findByUserIdAndRelationshipId(userId, relationshipId)
                .orElseGet(() -> new RelationshipReview(user, relationship));
        review.setStatus(status);   // also stamps reviewedAt
        review.setNote(note);
        review = reviewRepository.save(review);

        riskScoringService.rescorePortfolio(portfolioId, userId,
                "Relationship " + relationship.getFromCompany().getName() + " → "
                        + relationship.getToCompany().getName() + " marked " + status);
        return review;
    }

    @Transactional(readOnly = true)
    public Optional<RelationshipReview> findReview(Long userId, Long relationshipId) {
        return reviewRepository.findByUserIdAndRelationshipId(userId, relationshipId);
    }
}
