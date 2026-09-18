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
 * One row per AI/extraction call, regardless of outcome. AC 9.3: "audit trail of every
 * AI action (what was asked, what came back, when)". Written by
 * {@code GeminiClient}/{@code NewsAnalysisService} themselves, not by the callers that
 * use them, so logging cannot be accidentally skipped by a call site that forgets to —
 * the client IS the log point.
 *
 * <p>Scoped to a user because "target entity" and "what was asked" are only meaningful
 * in the context of one analyst's portfolio analysis run, and AC 1.3's isolation rule
 * applies here too: one user must not be able to read another's audit trail, even
 * though the underlying Company/Relationship data the calls were ABOUT may be shared.
 */
@Entity
@Table(name = "audit_log", indexes = @Index(name = "ix_audit_user_time", columnList = "user_id, created_at"))
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "action_type", nullable = false)
    private AuditActionType actionType;

    /** Company ticker/CIK/name, or "PORTFOLIO_GLOBAL" for portfolio-wide actions. */
    @Column(name = "target_entity", nullable = false, length = 200)
    private String targetEntity;

    @Column(name = "model_name", nullable = false, length = 100)
    private String modelName;

    /** What was sent — kept short (a summary/hash of the prompt), not the full text,
     *  to avoid this table becoming an unbounded duplicate of every filing fetched. */
    @Lob
    @Column(name = "input_reference")
    private String inputReference;

    @Lob
    @Column(name = "output_summary")
    private String outputSummary;

    @Column(name = "latency_ms")
    private Long latencyMs;

    @Column(nullable = false)
    private boolean success;

    @Column(name = "error_message", length = 2000)
    private String errorMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected AuditLog() {
        // JPA
    }

    public AuditLog(User user, AuditActionType actionType, String targetEntity, String modelName) {
        this.user = user;
        this.actionType = actionType;
        this.targetEntity = targetEntity;
        this.modelName = modelName;
    }

    public Long getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public AuditActionType getActionType() {
        return actionType;
    }

    public String getTargetEntity() {
        return targetEntity;
    }

    public String getModelName() {
        return modelName;
    }

    public String getInputReference() {
        return inputReference;
    }

    public void setInputReference(String inputReference) {
        this.inputReference = inputReference;
    }

    public String getOutputSummary() {
        return outputSummary;
    }

    public void setOutputSummary(String outputSummary) {
        this.outputSummary = outputSummary;
    }

    public Long getLatencyMs() {
        return latencyMs;
    }

    public void setLatencyMs(Long latencyMs) {
        this.latencyMs = latencyMs;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
