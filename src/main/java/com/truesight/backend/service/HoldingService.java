package com.truesight.backend.service;

import com.truesight.backend.common.NotFoundException;
import com.truesight.backend.domain.CoverageStatus;
import com.truesight.backend.domain.Holding;
import com.truesight.backend.repository.HoldingRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Same id+ownerId ownership pattern as PortfolioService, one level down: a holding is
 * only ever loaded via (holdingId, portfolioId, ownerId), verifying BOTH that the
 * holding belongs to the given portfolio AND that the portfolio belongs to the caller —
 * a holding id alone is not enough to prove ownership, since ids are not
 * per-user-scoped sequences.
 */
@Service
public class HoldingService {

    private final HoldingRepository holdingRepository;

    public HoldingService(HoldingRepository holdingRepository) {
        this.holdingRepository = holdingRepository;
    }

    @Transactional(readOnly = true)
    public List<Holding> listActive(Long portfolioId) {
        return holdingRepository.findActiveByPortfolioId(portfolioId);
    }

    @Transactional(readOnly = true)
    public Holding getOwned(Long holdingId, Long portfolioId, Long ownerId) {
        return holdingRepository.findByIdAndPortfolioIdAndOwnerId(holdingId, portfolioId, ownerId)
                .orElseThrow(() -> new NotFoundException("Holding not found"));
    }

    /**
     * AC 2.4: soft-delete with same-session undo. Sets the removed flag rather than
     * issuing a DELETE — see Holding.removed's Javadoc for why, and
     * HoldingRepository#findActiveByPortfolioId for how the rest of the app never sees
     * a removed holding without needing to remember to filter it out.
     */
    @Transactional
    public void remove(Long holdingId, Long portfolioId, Long ownerId) {
        Holding holding = getOwned(holdingId, portfolioId, ownerId);
        holding.setRemoved(true);
    }

    /** AC 2.4's undo: reverses remove() within the same session. Both are idempotent no-ops if already in that state. */
    @Transactional
    public void undoRemove(Long holdingId, Long portfolioId, Long ownerId) {
        Holding holding = getOwned(holdingId, portfolioId, ownerId);
        holding.setRemoved(false);
    }

    @Transactional(readOnly = true)
    public CoverageSummary getCoverageSummary(Long portfolioId) {
        long total = holdingRepository.countByPortfolioIdAndRemovedFalse(portfolioId);
        long covered = holdingRepository.countByPortfolioIdAndRemovedFalseAndCoverageStatus(portfolioId, CoverageStatus.COVERED);
        return new CoverageSummary(total, covered);
    }

    public record CoverageSummary(long totalHoldings, long coveredHoldings) {
    }
}
