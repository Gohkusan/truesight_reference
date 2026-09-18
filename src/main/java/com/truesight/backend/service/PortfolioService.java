package com.truesight.backend.service;

import com.truesight.backend.common.NotFoundException;
import com.truesight.backend.domain.Alert;
import com.truesight.backend.domain.Holding;
import com.truesight.backend.domain.Portfolio;
import com.truesight.backend.domain.RiskAssessment;
import com.truesight.backend.domain.User;
import com.truesight.backend.repository.AlertRepository;
import com.truesight.backend.repository.HoldingRepository;
import com.truesight.backend.repository.PortfolioRepository;
import com.truesight.backend.repository.RiskAssessmentRepository;
import com.truesight.backend.repository.UserRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The reference implementation of AC 1.3's ownership pattern: every read takes the
 * caller's user id alongside the resource id and goes through
 * PortfolioRepository#findByIdAndOwnerId, which returns Optional. Empty maps to
 * NotFoundException, which GlobalExceptionHandler turns into 404 — never 403, and
 * never a code path that could distinguish "doesn't exist" from "not yours" in the
 * response. Every future per-user service (HoldingService, AlertService, ...) should
 * follow this exact shape.
 */
@Service
public class PortfolioService {

    private final PortfolioRepository portfolioRepository;
    private final UserRepository userRepository;
    private final HoldingRepository holdingRepository;
    private final AlertRepository alertRepository;
    private final RiskAssessmentRepository riskAssessmentRepository;

    public PortfolioService(
            PortfolioRepository portfolioRepository,
            UserRepository userRepository,
            HoldingRepository holdingRepository,
            AlertRepository alertRepository,
            RiskAssessmentRepository riskAssessmentRepository
    ) {
        this.portfolioRepository = portfolioRepository;
        this.userRepository = userRepository;
        this.holdingRepository = holdingRepository;
        this.alertRepository = alertRepository;
        this.riskAssessmentRepository = riskAssessmentRepository;
    }

    @Transactional(readOnly = true)
    public List<Portfolio> listForUser(Long userId) {
        return portfolioRepository.findByOwnerIdOrderByCreatedAtAsc(userId);
    }

    @Transactional(readOnly = true)
    public Portfolio getOwned(Long portfolioId, Long userId) {
        return portfolioRepository.findByIdAndOwnerId(portfolioId, userId)
                .orElseThrow(() -> new NotFoundException("Portfolio not found"));
    }

    @Transactional
    public Portfolio create(Long userId, String name) {
        User owner = userRepository.getReferenceById(userId);
        return portfolioRepository.save(new Portfolio(owner, name));
    }

    @Transactional
    public Portfolio rename(Long portfolioId, Long userId, String newName) {
        Portfolio portfolio = getOwned(portfolioId, userId);
        portfolio.setName(newName);
        return portfolio;
    }

    /**
     * AC 2.7: "Delete requires confirmation and cascades to the portfolio's data
     * only." Confirmation itself is a frontend concern (a dialog before this call is
     * ever made); what this method guarantees is the "only".
     *
     * <p>Deletion is done explicitly here, child-table-first, rather than relying on
     * a database-level ON DELETE CASCADE or a JPA @OneToMany(cascade=ALL) on Portfolio.
     * Two reasons: first, it makes the blast radius readable in one place — anyone
     * studying this method sees exactly which tables a portfolio delete touches,
     * rather than having to trace cascade annotations scattered across several
     * entities. Second, and more importantly, it is what keeps this correct: Holding
     * and Alert belong to the portfolio and must go, but Relationship/Evidence/
     * RiskAssessment-on-Relationship live on the SHARED Company graph (see
     * Relationship.java's Javadoc) and must never be touched by one portfolio's
     * deletion, even though a RiskAssessment table join might otherwise make that easy
     * to get wrong with a blanket cascade.
     */
    @Transactional
    public void delete(Long portfolioId, Long userId) {
        Portfolio portfolio = getOwned(portfolioId, userId);

        // findByPortfolioId (not findActiveByPortfolioId): a portfolio delete must
        // remove EVERY holding row including already soft-removed ones (AC 2.4), or a
        // soft-removed holding becomes an orphaned reference once the portfolio is gone.
        List<Holding> allHoldings = holdingRepository.findByPortfolioId(portfolioId);
        for (Holding holding : allHoldings) {
            // Per-holding risk history (RiskAssessment rows keyed on holding_id) —
            // relationship-keyed risk history is untouched, see method Javadoc.
            riskAssessmentRepository.deleteAll(riskAssessmentRepository.findByHoldingIdOrderByAssessedAtAsc(holding.getId()));
        }
        holdingRepository.deleteAll(allHoldings);

        // Alert -> AlertSource cascades via Alert's own @OneToMany(cascade=ALL,
        // orphanRemoval=true) mapping, so deleting the Alert rows here is sufficient.
        alertRepository.deleteAll(alertRepository.findByPortfolioIdOrderByEventAtDesc(portfolioId));

        portfolioRepository.delete(portfolio);
    }
}
