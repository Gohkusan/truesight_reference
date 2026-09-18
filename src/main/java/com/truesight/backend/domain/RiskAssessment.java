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
import jakarta.persistence.Lob;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * One risk score, for one holding OR one relationship, as of one point in time.
 * Deliberately append-only — a new refresh INSERTS a new row rather than UPDATE-ing the
 * previous one. That single decision is what makes both AC 6.4 ("previous score,
 * current score, and the input that caused the change") and AC 8.3 ("a history of a
 * relationship's risk over time... if there is one filing, the history has one point")
 * fall out of the same table for free: "current" is just "latest row for this
 * holding/relationship", and "history" is just "all rows", ordered by
 * {@code assessedAt}. No separate snapshot or audit table needed.
 *
 * <p>Exactly one of {@code holding} / {@code relationship} is set, never both — this is
 * a discriminated union, not two independent foreign keys that happen to coexist. See
 * {@code RiskScoringService} for which factors feed each kind of score.
 */
@Entity
@Table(
        name = "risk_assessment",
        indexes = {
                @Index(name = "ix_risk_holding", columnList = "holding_id, assessed_at"),
                @Index(name = "ix_risk_relationship", columnList = "relationship_id, assessed_at")
        }
)
public class RiskAssessment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "holding_id")
    private Holding holding;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "relationship_id")
    private Relationship relationship;

    /** 0-100, or null when severity is UNKNOWN (AC 6.1: never a fabricated number). */
    @Column(name = "score")
    private Integer score;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RiskSeverity severity = RiskSeverity.UNKNOWN;

    /**
     * JSON array of factor strings, e.g. ["single-source", "shared supplier",
     * "negative news: 2 articles", "geographic concentration: 62% Taiwan"]. Stored as
     * a JSON blob rather than a normalised factors table because factors are
     * display-only explanation text (AC 6.1: "the explanation lists the factors used"),
     * never queried or aggregated individually — a normalised table would add join
     * complexity with no corresponding read-path benefit.
     */
    @Lob
    @Column(name = "factors_json")
    private String factorsJson;

    /**
     * What changed since the previous assessment for this same holding/relationship,
     * e.g. "New 10-K filed 2025-03-01 added a single-source supplier mention". Null on
     * the very first assessment, which has no "previous" to compare against. Populated
     * by RiskScoringService by diffing against the prior row — see AC 8.2.
     */
    @Column(name = "change_reason", length = 1000)
    private String changeReason;

    @Column(name = "assessed_at", nullable = false)
    private Instant assessedAt = Instant.now();

    protected RiskAssessment() {
        // JPA
    }

    public static RiskAssessment forHolding(Holding holding) {
        RiskAssessment ra = new RiskAssessment();
        ra.holding = holding;
        return ra;
    }

    public static RiskAssessment forRelationship(Relationship relationship) {
        RiskAssessment ra = new RiskAssessment();
        ra.relationship = relationship;
        return ra;
    }

    public Long getId() {
        return id;
    }

    public Holding getHolding() {
        return holding;
    }

    public Relationship getRelationship() {
        return relationship;
    }

    public Integer getScore() {
        return score;
    }

    public void setScore(Integer score) {
        this.score = score;
    }

    public RiskSeverity getSeverity() {
        return severity;
    }

    public void setSeverity(RiskSeverity severity) {
        this.severity = severity;
    }

    public String getFactorsJson() {
        return factorsJson;
    }

    public void setFactorsJson(String factorsJson) {
        this.factorsJson = factorsJson;
    }

    public String getChangeReason() {
        return changeReason;
    }

    public void setChangeReason(String changeReason) {
        this.changeReason = changeReason;
    }

    public Instant getAssessedAt() {
        return assessedAt;
    }
}
