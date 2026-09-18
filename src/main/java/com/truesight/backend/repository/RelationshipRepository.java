package com.truesight.backend.repository;

import com.truesight.backend.domain.Relationship;
import com.truesight.backend.domain.RelationshipType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RelationshipRepository extends JpaRepository<Relationship, Long> {

    Optional<Relationship> findByFromCompanyIdAndToCompanyIdAndRelationshipType(
            Long fromCompanyId, Long toCompanyId, RelationshipType relationshipType);

    List<Relationship> findByFromCompanyId(Long fromCompanyId);

    List<Relationship> findByToCompanyId(Long toCompanyId);

    /**
     * Every SUPPLIER-typed edge touching any of the given companies, in either
     * direction. This is the raw edge set GraphAssemblyService walks outward from a
     * portfolio's holdings to build tier-1 and tier-2 supplier layers — see AC 4.1.
     */
    @Query("""
            select r from Relationship r
            where r.relationshipType = com.truesight.backend.domain.RelationshipType.SUPPLIER
              and (r.fromCompany.id in :companyIds or r.toCompany.id in :companyIds)
            """)
    List<Relationship> findSupplierEdgesTouching(@Param("companyIds") List<Long> companyIds);
}
