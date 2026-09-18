package com.truesight.backend.ingestion;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;

/**
 * Tracks per-portfolio analysis progress and cancel signals — AC 2.5: "Progress shows
 * 'x of N holdings analysed' and per-holding status... Cancel stops any further SEC and
 * LLM calls."
 *
 * <p>Deliberately in-memory, not a JPA entity. This was a genuine question worth
 * settling rather than defaulting to "everything is an entity": what needs to SURVIVE
 * is per-holding coverage status, which already lives durably on the Holding row
 * itself (Holding.coverageStatus/failureReason) — a page refresh mid-analysis still
 * shows correct state by querying holdings directly. What this class adds on top is
 * only the LIVE signal — "is a run in progress right now, and has the user asked to
 * stop it" — which only needs to exist for the lifetime of one analysis run on one
 * running server process, and would in fact be WRONG to persist: a "cancelled" flag
 * surviving a server restart into the next run would incorrectly cancel a future,
 * unrelated analysis the user never asked to stop.
 *
 * <p>One instance per Spring context (singleton bean), a ConcurrentHashMap keyed by
 * portfolio id — a single-process reference implementation; a multi-instance deployment
 * would need this to move to a shared store (e.g. Redis) for cancel signals to reach
 * every instance, noted here as a real scaling limit rather than hidden.
 */
@Component
public class AnalysisProgressTracker {

    public record Progress(int total, int completed, int failed, boolean cancelled, boolean done) {
    }

    private record RunState(int total, java.util.concurrent.atomic.AtomicInteger completed,
                             java.util.concurrent.atomic.AtomicInteger failed,
                             AtomicBoolean cancelRequested, AtomicBoolean done) {
    }

    private final Map<Long, RunState> runsByPortfolioId = new ConcurrentHashMap<>();

    public void start(Long portfolioId, int totalHoldings) {
        runsByPortfolioId.put(portfolioId, new RunState(
                totalHoldings,
                new java.util.concurrent.atomic.AtomicInteger(0),
                new java.util.concurrent.atomic.AtomicInteger(0),
                new AtomicBoolean(false),
                new AtomicBoolean(false)
        ));
    }

    public void recordCompleted(Long portfolioId) {
        RunState state = runsByPortfolioId.get(portfolioId);
        if (state != null) {
            state.completed().incrementAndGet();
        }
    }

    public void recordFailed(Long portfolioId) {
        RunState state = runsByPortfolioId.get(portfolioId);
        if (state != null) {
            state.failed().incrementAndGet();
        }
    }

    public void finish(Long portfolioId) {
        RunState state = runsByPortfolioId.get(portfolioId);
        if (state != null) {
            state.done().set(true);
        }
    }

    /** AC 2.5's cancel action. Checked by IngestionService between holdings, never mid-holding. */
    public void requestCancel(Long portfolioId) {
        RunState state = runsByPortfolioId.get(portfolioId);
        if (state != null) {
            state.cancelRequested().set(true);
        }
    }

    public boolean isCancelRequested(Long portfolioId) {
        RunState state = runsByPortfolioId.get(portfolioId);
        return state != null && state.cancelRequested().get();
    }

    public Progress getProgress(Long portfolioId) {
        RunState state = runsByPortfolioId.get(portfolioId);
        if (state == null) {
            return new Progress(0, 0, 0, false, true);
        }
        return new Progress(state.total(), state.completed().get(), state.failed().get(),
                state.cancelRequested().get(), state.done().get());
    }
}
