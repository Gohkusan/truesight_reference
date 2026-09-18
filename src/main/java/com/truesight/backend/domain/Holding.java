package com.truesight.backend.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * One row of the user's portfolio: "I hold company X at weight Y%". This is the entity
 * per-user isolation actually protects (AC 1.3) — every query for holdings must filter
 * by the owning portfolio's user id, enforced in the service layer (see
 * {@code AbstractOwnedResourceService}), not just at the controller.
 *
 * <p>Weight and shares are BigDecimal, not double: this is a financial tool and AC 2.1
 * requires that missing weights/shares are shown blank rather than defaulted to zero or
 * any other number, so the nullability of these fields is load-bearing — a primitive
 * double could not represent "not provided" at all.
 */
@Entity
@Table(name = "holding", indexes = @Index(name = "ix_holding_portfolio", columnList = "portfolio_id"))
public class Holding {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "portfolio_id", nullable = false)
    private Portfolio portfolio;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "company_id", nullable = false)
    private Company company;

    /** Null means "not provided in the CSV" — never defaulted (AC 2.1). */
    @Column(name = "weight_percent", precision = 9, scale = 4)
    private BigDecimal weightPercent;

    @Column(precision = 20, scale = 4)
    private BigDecimal shares;

    @Column(name = "market_value", precision = 20, scale = 2)
    private BigDecimal marketValue;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CoverageStatus coverageStatus = CoverageStatus.PENDING;

    /** Human-readable reason shown next to a FAILED status (AC 2.5: "listed with a reason"). */
    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "last_analysed_at")
    private Instant lastAnalysedAt;

    /**
     * Set when this holding was added via "add by ticker" (AC 2.3) or survived a CSV
     * diff as newly added (AC 2.6), and cleared at the start of the user's NEXT login
     * session. Drives the "highlighted in the list and graph until next session" rule —
     * a boolean instead of a timestamp comparison because "until next session" is a
     * session boundary, not a time duration.
     */
    @Column(name = "is_newly_added", nullable = false)
    private boolean newlyAdded = false;

    /**
     * Soft delete for AC 2.4's "Undo is available for the rest of the session": removal
     * sets this instead of issuing a DELETE, so undo is just clearing the flag. A
     * scheduled or explicit purge could hard-delete rows past a certain age later; the
     * reference implementation does not need that yet.
     */
    @Column(name = "is_removed", nullable = false)
    private boolean removed = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected Holding() {
        // JPA
    }

    public Holding(Portfolio portfolio, Company company) {
        this.portfolio = portfolio;
        this.company = company;
    }

    public Long getId() {
        return id;
    }

    public Portfolio getPortfolio() {
        return portfolio;
    }

    public Company getCompany() {
        return company;
    }

    public BigDecimal getWeightPercent() {
        return weightPercent;
    }

    public void setWeightPercent(BigDecimal weightPercent) {
        this.weightPercent = weightPercent;
    }

    public BigDecimal getShares() {
        return shares;
    }

    public void setShares(BigDecimal shares) {
        this.shares = shares;
    }

    public BigDecimal getMarketValue() {
        return marketValue;
    }

    public void setMarketValue(BigDecimal marketValue) {
        this.marketValue = marketValue;
    }

    public CoverageStatus getCoverageStatus() {
        return coverageStatus;
    }

    public void setCoverageStatus(CoverageStatus coverageStatus) {
        this.coverageStatus = coverageStatus;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public void setFailureReason(String failureReason) {
        this.failureReason = failureReason;
    }

    public Instant getLastAnalysedAt() {
        return lastAnalysedAt;
    }

    public void setLastAnalysedAt(Instant lastAnalysedAt) {
        this.lastAnalysedAt = lastAnalysedAt;
    }

    public boolean isNewlyAdded() {
        return newlyAdded;
    }

    public void setNewlyAdded(boolean newlyAdded) {
        this.newlyAdded = newlyAdded;
    }

    public boolean isRemoved() {
        return removed;
    }

    public void setRemoved(boolean removed) {
        this.removed = removed;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
