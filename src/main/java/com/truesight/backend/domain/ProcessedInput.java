package com.truesight.backend.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

/**
 * Marks one filing (by SEC accession number) or one article (by URL) as already
 * processed, so the scheduled daily refresh (AC 8.1) can skip it and reuse cached
 * results instead of re-running SEC fetch + Gemini extraction on unchanged input.
 *
 * <p>This is the entity that makes "only changed inputs are re-analysed... this keeps
 * LLM spend bounded" true. Without it, a refresh has no way to distinguish "this filing
 * was already analysed and genuinely mentions no suppliers" from "this filing has never
 * been looked at" — both look like zero relationships from the Relationship table
 * alone, since Relationship only records positive extractions. This table is what
 * turns "we found nothing" into a recorded fact rather than an absence indistinguishable
 * from "we haven't checked yet".
 *
 * <p>Keyed by accession number OR article URL, not by company — the same filing being
 * newly relevant to a second holding (e.g. a company added to a second portfolio) is
 * still the same accession number, and must still be treated as already-processed.
 */
@Entity
@Table(
        name = "processed_input",
        uniqueConstraints = @UniqueConstraint(name = "uk_processed_input_key", columnNames = "input_key")
)
public class ProcessedInput {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ProcessedInputType type;

    /** SEC accession number (e.g. "0000320193-24-000123") or the article's URL. */
    @Column(name = "input_key", nullable = false, length = 1000)
    private String inputKey;

    /**
     * How many relationships this input yielded on its LAST successful processing.
     * Purely informational (surfaced in the audit trail / refresh summary); the
     * skip decision itself only depends on a row existing for this key.
     */
    @Column(name = "relationships_found")
    private Integer relationshipsFound;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt = Instant.now();

    protected ProcessedInput() {
        // JPA
    }

    public ProcessedInput(ProcessedInputType type, String inputKey) {
        this.type = type;
        this.inputKey = inputKey;
    }

    public Long getId() {
        return id;
    }

    public ProcessedInputType getType() {
        return type;
    }

    public String getInputKey() {
        return inputKey;
    }

    public Integer getRelationshipsFound() {
        return relationshipsFound;
    }

    public void setRelationshipsFound(Integer relationshipsFound) {
        this.relationshipsFound = relationshipsFound;
    }

    public Instant getProcessedAt() {
        return processedAt;
    }

    public void setProcessedAt(Instant processedAt) {
        this.processedAt = processedAt;
    }
}
