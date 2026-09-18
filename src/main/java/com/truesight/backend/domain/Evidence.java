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
import java.time.LocalDate;

/**
 * One source backing one {@link Relationship}: a document, a date, a link, and — the
 * part that matters — the EXACT quoted excerpt that supports the claim.
 *
 * <p>This entity only stores the excerpt; it does not itself enforce that the excerpt
 * is genuine. That enforcement is the verbatim excerpt guard, a non-negotiable from the
 * build brief: every Evidence row this app ever persists must first have passed
 * {@code com.truesight.backend.ingestion.llm.ExcerptVerificationService#verify}, which
 * does a normalised literal substring search against the actual fetched filing text
 * and discards (never persists) any excerpt that is not found verbatim. By the time a
 * row exists in this table, it has already passed that check — there is no "unverified"
 * state to represent here, because an unverified excerpt is never allowed to become a
 * row at all. See AC 5.1: "Relationships without a passing source are never shown."
 */
@Entity
@Table(name = "evidence", indexes = @Index(name = "ix_evidence_relationship", columnList = "relationship_id"))
public class Evidence {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "relationship_id", nullable = false)
    private Relationship relationship;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false)
    private SourceType sourceType;

    /**
     * The exact quoted text, already confirmed present verbatim (after whitespace/quote
     * normalisation — see the guard's Javadoc for exactly what "verbatim" tolerates) in
     * the fetched source document. Never edited after verification: an edited excerpt
     * would no longer be the thing that was checked.
     */
    @Lob
    @Column(nullable = false)
    private String excerpt;

    /** Filing date for SEC sources, article publication date for news. */
    @Column(name = "source_date")
    private LocalDate sourceDate;

    /** Deep link: SEC Archives URL for filings, article URL for news. */
    @Column(name = "source_url", length = 1000)
    private String sourceUrl;

    /** SEC accession number, e.g. "0000320193-24-000123". Null for news sources. */
    @Column(name = "accession_number", length = 30)
    private String accessionNumber;

    protected Evidence() {
        // JPA
    }

    public Evidence(Relationship relationship, SourceType sourceType, String excerpt) {
        this.relationship = relationship;
        this.sourceType = sourceType;
        this.excerpt = excerpt;
    }

    public Long getId() {
        return id;
    }

    public Relationship getRelationship() {
        return relationship;
    }

    public SourceType getSourceType() {
        return sourceType;
    }

    public String getExcerpt() {
        return excerpt;
    }

    public LocalDate getSourceDate() {
        return sourceDate;
    }

    public void setSourceDate(LocalDate sourceDate) {
        this.sourceDate = sourceDate;
    }

    public String getSourceUrl() {
        return sourceUrl;
    }

    public void setSourceUrl(String sourceUrl) {
        this.sourceUrl = sourceUrl;
    }

    public String getAccessionNumber() {
        return accessionNumber;
    }

    public void setAccessionNumber(String accessionNumber) {
        this.accessionNumber = accessionNumber;
    }
}
