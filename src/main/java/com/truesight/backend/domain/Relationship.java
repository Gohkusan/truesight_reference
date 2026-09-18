package com.truesight.backend.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * A directed supply-chain edge between two companies: "FROM is a SUPPLIER of TO" or
 * "FROM is a CUSTOMER of TO". Like {@link Company}, this table is NOT scoped to a user
 * — a supplier relationship discovered from one user's filing analysis is a fact about
 * the world and should be visible (subject to its own evidence and confidence) to any
 * user who happens to hold a connected company, otherwise two users analysing the same
 * public company would get two disconnected, duplicated graphs of the same real
 * dependency.
 *
 * <p>What IS per-user is a person's judgement about whether to trust a given
 * relationship — see {@link RelationshipReview}. That is a deliberate split: the
 * underlying evidence is shared and objective, but "I reject this" is one analyst's
 * call and must never silently remove the edge from another analyst's graph.
 *
 * <p>tier is stored here (not just derived at query time) because a company can be
 * reached from a holding via more than one path at different depths, and AC 4.1 wants
 * the SHORTEST such depth used for layout radius; recomputing that per request across
 * an arbitrary portfolio graph is a BFS over persisted edges, done in
 * {@code GraphAssemblyService}, not something to bake into the entity as a fixed value
 * — this column instead records the tier AT WHICH THIS SPECIFIC EDGE was discovered
 * during extraction, i.e. how deep the analysis walked to find it.
 */
@Entity
@Table(
        name = "relationship",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_relationship_edge",
                columnNames = {"from_company_id", "to_company_id", "relationship_type"}
        ),
        indexes = {
                @Index(name = "ix_relationship_from", columnList = "from_company_id"),
                @Index(name = "ix_relationship_to", columnList = "to_company_id")
        }
)
public class Relationship {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "from_company_id", nullable = false)
    private Company fromCompany;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "to_company_id", nullable = false)
    private Company toCompany;

    @Enumerated(EnumType.STRING)
    @Column(name = "relationship_type", nullable = false)
    private RelationshipType relationshipType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Criticality criticality = Criticality.DIVERSIFIED;

    /** 0-100. Null when the source filing did not state a specific figure. */
    @Column(name = "dependency_percent")
    private Integer dependencyPercent;

    @Column(name = "disruption_reason", length = 2000)
    private String disruptionReason;

    /**
     * The tier depth (1 or 2) at which extraction discovered this edge. See class
     * Javadoc: this is provenance of the discovery walk, not necessarily the tier the
     * frontend renders it at for a specific holding's graph (that is recomputed by
     * shortest-path-from-any-holding in GraphAssemblyService).
     */
    @Column(nullable = false)
    private int tier = 1;

    /**
     * AC 5.2's single confidence number, 0-100, computed by RiskScoringService from
     * source count/recency/explicitness and capped per AC 9.1/5.5. Stored (not
     * recomputed on every read) so "the same score is used everywhere in the app" is
     * enforced by construction — every screen reads this one column, there is no
     * second code path that could drift from it.
     */
    @Column(name = "confidence_score")
    private Integer confidenceScore;

    /**
     * AC 5.5: when two sources disagree (e.g. one says single-source, another says
     * dual-source), both claims are shown side by side and confidence is capped at
     * Medium. This flag is what triggers that cap and that UI treatment.
     */
    @Column(name = "sources_conflict", nullable = false)
    private boolean sourcesConflict = false;

    /**
     * AC 5.3: true when the newest fetched filing for the FROM/TO holding no longer
     * mentions the counterparty. Such relationships are excluded from risk scoring
     * unless the user explicitly confirms them (see RelationshipReview).
     */
    @Column(name = "no_longer_disclosed", nullable = false)
    private boolean noLongerDisclosed = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @OneToMany(mappedBy = "relationship", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Evidence> evidence = new ArrayList<>();

    protected Relationship() {
        // JPA
    }

    public Relationship(Company fromCompany, Company toCompany, RelationshipType relationshipType) {
        this.fromCompany = fromCompany;
        this.toCompany = toCompany;
        this.relationshipType = relationshipType;
    }

    public Long getId() {
        return id;
    }

    public Company getFromCompany() {
        return fromCompany;
    }

    public Company getToCompany() {
        return toCompany;
    }

    public RelationshipType getRelationshipType() {
        return relationshipType;
    }

    public Criticality getCriticality() {
        return criticality;
    }

    public void setCriticality(Criticality criticality) {
        this.criticality = criticality;
    }

    public Integer getDependencyPercent() {
        return dependencyPercent;
    }

    public void setDependencyPercent(Integer dependencyPercent) {
        this.dependencyPercent = dependencyPercent;
    }

    public String getDisruptionReason() {
        return disruptionReason;
    }

    public void setDisruptionReason(String disruptionReason) {
        this.disruptionReason = disruptionReason;
    }

    public int getTier() {
        return tier;
    }

    public void setTier(int tier) {
        this.tier = tier;
    }

    public Integer getConfidenceScore() {
        return confidenceScore;
    }

    public void setConfidenceScore(Integer confidenceScore) {
        this.confidenceScore = confidenceScore;
    }

    public boolean isSourcesConflict() {
        return sourcesConflict;
    }

    public void setSourcesConflict(boolean sourcesConflict) {
        this.sourcesConflict = sourcesConflict;
    }

    public boolean isNoLongerDisclosed() {
        return noLongerDisclosed;
    }

    public void setNoLongerDisclosed(boolean noLongerDisclosed) {
        this.noLongerDisclosed = noLongerDisclosed;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void touch() {
        this.updatedAt = Instant.now();
    }

    public List<Evidence> getEvidence() {
        return evidence;
    }
}
