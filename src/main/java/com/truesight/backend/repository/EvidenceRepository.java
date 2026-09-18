package com.truesight.backend.repository;

import com.truesight.backend.domain.Evidence;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EvidenceRepository extends JpaRepository<Evidence, Long> {

    List<Evidence> findByRelationshipId(Long relationshipId);
}
