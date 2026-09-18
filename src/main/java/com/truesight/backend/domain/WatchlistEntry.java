package com.truesight.backend.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

/**
 * A company the user wants alerts for even though it is not (or not yet) an actual
 * portfolio holding (AC 10.3). Deliberately a separate table from Holding rather than
 * a "phantom holding with null weight": a watchlist entry has no weight, no coverage
 * status, and must never appear in the holdings table or count toward portfolio
 * coverage — conflating the two would corrupt AC 2.2's coverage denominator ("n of N
 * holdings covered").
 */
@Entity
@Table(
        name = "watchlist_entry",
        uniqueConstraints = @UniqueConstraint(name = "uk_watchlist_user_company", columnNames = {"user_id", "company_id"}),
        indexes = @Index(name = "ix_watchlist_user", columnList = "user_id")
)
public class WatchlistEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "company_id", nullable = false)
    private Company company;

    @Column(name = "added_at", nullable = false)
    private Instant addedAt = Instant.now();

    protected WatchlistEntry() {
        // JPA
    }

    public WatchlistEntry(User user, Company company) {
        this.user = user;
        this.company = company;
    }

    public Long getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public Company getCompany() {
        return company;
    }

    public Instant getAddedAt() {
        return addedAt;
    }
}
