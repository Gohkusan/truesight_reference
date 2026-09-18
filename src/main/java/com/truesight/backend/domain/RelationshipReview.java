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
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

/**
 * One user's confirm/reject judgement on one {@link Relationship} (AC 5.4). Kept
 * separate from Relationship itself — see the Javadoc on Relationship for why: the
 * underlying edge and its evidence are shared, factual, and the same for every user who
 * can see them, but "I reject this" is this one analyst's call. Two different asset
 * managers holding the same stock are allowed to disagree about whether a discovered
 * dependency is worth trusting, and one's rejection must never remove the edge from the
 * other's graph or risk calculation.
 *
 * <p>Also true to AC 5.4's "both actions are reversible": there is no delete-only
 * REJECTED state — status can be flipped back to PENDING or to CONFIRMED at any time,
 * this row is just overwritten (see {@code RelationshipReviewService}).
 */
@Entity
@Table(
        name = "relationship_review",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_review_user_relationship",
                columnNames = {"user_id", "relationship_id"}
        ),
        indexes = @Index(name = "ix_review_relationship", columnList = "relationship_id")
)
public class RelationshipReview {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "relationship_id", nullable = false)
    private Relationship relationship;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReviewStatus status = ReviewStatus.PENDING;

    @Column(length = 1000)
    private String note;

    @Column(name = "reviewed_at", nullable = false)
    private Instant reviewedAt = Instant.now();

    protected RelationshipReview() {
        // JPA
    }

    public RelationshipReview(User user, Relationship relationship) {
        this.user = user;
        this.relationship = relationship;
    }

    public Long getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public Relationship getRelationship() {
        return relationship;
    }

    public ReviewStatus getStatus() {
        return status;
    }

    public void setStatus(ReviewStatus status) {
        this.status = status;
        this.reviewedAt = Instant.now();
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public Instant getReviewedAt() {
        return reviewedAt;
    }
}
