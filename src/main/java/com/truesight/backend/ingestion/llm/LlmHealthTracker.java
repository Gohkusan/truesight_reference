package com.truesight.backend.ingestion.llm;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

/**
 * Remembers the most recent LLM outcome so the frontend can render AC 9.2's banners
 * ("LLM errors, rate limits, and budget exhaustion each show a distinct banner... with
 * my previous results still visible"). In-memory on purpose, same reasoning as
 * AnalysisProgressTracker: this is a live status light, not a record — the durable
 * record of every call is the AuditLog table. A status that survived a restart would
 * show a stale "quota exhausted" banner long after the quota reset.
 */
@Component
public class LlmHealthTracker {

    public record Status(boolean configured, LlmUnavailableException.Kind lastFailureKind,
                         String lastFailureMessage, Instant lastFailureAt, Instant lastSuccessAt) {
    }

    private final GeminiClient geminiClient;
    private final AtomicReference<LlmUnavailableException> lastFailure = new AtomicReference<>();
    private final AtomicReference<Instant> lastFailureAt = new AtomicReference<>();
    private final AtomicReference<Instant> lastSuccessAt = new AtomicReference<>();

    public LlmHealthTracker(GeminiClient geminiClient) {
        this.geminiClient = geminiClient;
    }

    public void recordSuccess() {
        lastSuccessAt.set(Instant.now());
        // A success after a failure clears the banner: the condition has resolved.
        lastFailure.set(null);
        lastFailureAt.set(null);
    }

    public void recordFailure(Throwable t) {
        LlmUnavailableException llm = LlmUnavailableException.findIn(t);
        if (llm == null) {
            llm = new LlmUnavailableException(LlmUnavailableException.Kind.ERROR,
                    t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage(), t);
        }
        lastFailure.set(llm);
        lastFailureAt.set(Instant.now());
    }

    public Status status() {
        LlmUnavailableException f = lastFailure.get();
        return new Status(
                geminiClient.isConfigured(),
                f == null ? null : f.getKind(),
                f == null ? null : f.userFacingSummary(),
                lastFailureAt.get(),
                lastSuccessAt.get());
    }
}
