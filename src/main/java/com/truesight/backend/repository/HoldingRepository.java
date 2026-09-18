package com.truesight.backend.repository;

import com.truesight.backend.domain.CoverageStatus;
import com.truesight.backend.domain.Holding;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface HoldingRepository extends JpaRepository<Holding, Long> {

    /**
     * The "not removed" filter here (rather than in every call site) is what makes
     * AC 2.4's soft-delete-with-undo transparent to the rest of the app: everything
     * downstream of this repository simply never sees a removed holding, without
     * having to remember to filter it out itself.
     */
    @Query("select h from Holding h where h.portfolio.id = :portfolioId and h.removed = false")
    List<Holding> findActiveByPortfolioId(@Param("portfolioId") Long portfolioId);

    /**
     * Unlike findActiveByPortfolioId, includes soft-removed rows too (see
     * Holding.removed's Javadoc). Only meant for portfolio deletion, where EVERY
     * holding row under the portfolio — active or already removed — must go, since a
     * soft-removed holding would otherwise become an orphaned foreign-key reference
     * once the parent portfolio row is gone.
     */
    List<Holding> findByPortfolioId(Long portfolioId);

    @Query("""
            select h from Holding h
            where h.id = :id and h.portfolio.id = :portfolioId and h.portfolio.owner.id = :ownerId
            """)
    Optional<Holding> findByIdAndPortfolioIdAndOwnerId(
            @Param("id") Long id, @Param("portfolioId") Long portfolioId, @Param("ownerId") Long ownerId);

    @Query("""
            select h from Holding h
            where h.portfolio.id = :portfolioId and h.company.id = :companyId and h.removed = false
            """)
    Optional<Holding> findActiveByPortfolioIdAndCompanyId(
            @Param("portfolioId") Long portfolioId, @Param("companyId") Long companyId);

    long countByPortfolioIdAndRemovedFalse(Long portfolioId);

    long countByPortfolioIdAndRemovedFalseAndCoverageStatus(Long portfolioId, CoverageStatus status);

    List<Holding> findByPortfolioIdAndRemovedFalseAndCoverageStatus(Long portfolioId, CoverageStatus status);
}
