package com.truesight.backend.domain;

import jakarta.persistence.CascadeType;
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
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * A detected event affecting a holding or a tier-1 supplier (Epic 7). Scoped to a
 * Portfolio (not to Company, unlike Relationship/Evidence) because "why it matters" is
 * inherently relative to a specific user's holdings — the same news story produces a
 * different "affected holdings" list, and therefore a different severity, for two
 * different portfolios that happen to share a supplier.
 *
 * <p>{@code sourceCompany} is the entity the news is actually ABOUT (may be a holding
 * itself or one of its tier-1 suppliers, per AC 7.1's "news is fetched for each holding
 * and tier-1 supplier"). {@code affectedHoldingIds} is NOT a stored column — it is
 * derived at read time from the graph ("a supplier event maps to every holding
 * connected to that supplier", AC 7.2), because the correct answer can change as new
 * relationships are discovered even after the alert itself was created.
 */
@Entity
@Table(name = "alert", indexes = @Index(name = "ix_alert_portfolio", columnList = "portfolio_id, status"))
public class Alert {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "portfolio_id", nullable = false)
    private Portfolio portfolio;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "source_company_id", nullable = false)
    private Company sourceCompany;

    @Column(nullable = false, length = 500)
    private String headline;

    @Column(length = 4000)
    private String summary;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AlertSeverity severity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AlertStatus status = AlertStatus.NEW;

    @Enumerated(EnumType.STRING)
    @Column(name = "dismiss_reason")
    private DismissReason dismissReason;

    /**
     * When the underlying event happened, per the source article(s) — distinct from
     * {@code createdAt} (when TrueSight found it). AC 7.1 is explicit that these must
     * not be conflated: a story from three days ago that TrueSight only just indexed
     * should still show a three-day-old event timestamp, not today's date.
     */
    @Column(name = "event_at", nullable = false)
    private Instant eventAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @OneToMany(mappedBy = "alert", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<AlertSource> sources = new ArrayList<>();

    protected Alert() {
        // JPA
    }

    public Alert(Portfolio portfolio, Company sourceCompany, String headline, AlertSeverity severity, Instant eventAt) {
        this.portfolio = portfolio;
        this.sourceCompany = sourceCompany;
        this.headline = headline;
        this.severity = severity;
        this.eventAt = eventAt;
    }

    public Long getId() {
        return id;
    }

    public Portfolio getPortfolio() {
        return portfolio;
    }

    public Company getSourceCompany() {
        return sourceCompany;
    }

    public String getHeadline() {
        return headline;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public AlertSeverity getSeverity() {
        return severity;
    }

    public AlertStatus getStatus() {
        return status;
    }

    public void setStatus(AlertStatus status) {
        this.status = status;
    }

    public DismissReason getDismissReason() {
        return dismissReason;
    }

    public void setDismissReason(DismissReason dismissReason) {
        this.dismissReason = dismissReason;
    }

    public Instant getEventAt() {
        return eventAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public List<AlertSource> getSources() {
        return sources;
    }
}
