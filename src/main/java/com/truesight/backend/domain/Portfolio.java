package com.truesight.backend.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * A named book of holdings (AC 2.7: "keep several named portfolios and switch between
 * them"). Every other per-user entity in this app (Holding, Alert, ChangeLog) hangs off
 * a Portfolio rather than directly off User, so "switch active portfolio" is just
 * "change which portfolio id the frontend sends" — no duplication of query logic
 * between a single-portfolio and multi-portfolio code path.
 */
@Entity
@Table(name = "portfolio")
public class Portfolio {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User owner;

    @Column(nullable = false)
    private String name;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    /**
     * Bumped whenever holdings, relationships, or risk scores under this portfolio
     * change. Read by the dashboard's "last updated" displays (AC 3.3) without having
     * to MAX() across several child tables on every page load.
     */
    @Column(name = "last_analysed_at")
    private Instant lastAnalysedAt;

    protected Portfolio() {
        // JPA
    }

    public Portfolio(User owner, String name) {
        this.owner = owner;
        this.name = name;
    }

    public Long getId() {
        return id;
    }

    public User getOwner() {
        return owner;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getLastAnalysedAt() {
        return lastAnalysedAt;
    }

    public void setLastAnalysedAt(Instant lastAnalysedAt) {
        this.lastAnalysedAt = lastAnalysedAt;
    }
}
