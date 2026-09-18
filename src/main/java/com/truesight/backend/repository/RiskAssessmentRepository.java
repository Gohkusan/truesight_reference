package com.truesight.backend.repository;

import com.truesight.backend.domain.RiskAssessment;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RiskAssessmentRepository extends JpaRepository<RiskAssessment, Long> {

    /** Most recent row for a holding = its current score (see RiskAssessment.java Javadoc). */
    Optional<RiskAssessment> findTopByHoldingIdOrderByAssessedAtDesc(Long holdingId);

    Optional<RiskAssessment> findTopByRelationshipIdOrderByAssessedAtDesc(Long relationshipId);

    /** Full history, oldest first, for AC 8.3's "history of a relationship's risk over time". */
    List<RiskAssessment> findByRelationshipIdOrderByAssessedAtAsc(Long relationshipId);

    List<RiskAssessment> findByHoldingIdOrderByAssessedAtAsc(Long holdingId);
}
