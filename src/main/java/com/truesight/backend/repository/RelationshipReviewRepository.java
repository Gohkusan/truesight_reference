package com.truesight.backend.repository;

import com.truesight.backend.domain.RelationshipReview;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RelationshipReviewRepository extends JpaRepository<RelationshipReview, Long> {

    Optional<RelationshipReview> findByUserIdAndRelationshipId(Long userId, Long relationshipId);

    List<RelationshipReview> findByUserId(Long userId);
}
