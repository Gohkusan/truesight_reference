package com.truesight.backend.ingestion.sec;

import com.truesight.backend.config.TrueSightProperties;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * Enforces SEC EDGAR's Fair Access policy (documented max ~10 req/s; SEC has
 * historically blocked IPs that exceed it). Two independent mechanisms, matching the
 * two ways a naive parallel ingestion pipeline could violate the policy:
 *
 * <ol>
 *   <li><b>A bounded semaphore</b> caps how many requests are in flight AT ONCE,
 *       regardless of how many holdings are being analysed in parallel
 *       (CompletableFuture-based, per the ingestion pipeline's design). Without this,
 *       analysing 20 holdings concurrently would fire 20 simultaneous SEC requests.</li>
 *   <li><b>A minimum-interval pacing lock</b> additionally spaces out the STARTS of
 *       consecutive requests even when under the concurrency cap, so four workers each
 *       finishing quickly in a burst can't fire four requests in the same millisecond.
 *       This is what actually keeps steady-state throughput under the req/s ceiling;
 *       the semaphore alone bounds concurrency, not rate.</li>
 * </ol>
 *
 * <p>Every SEC HTTP call in this app — filing fetch, ticker index sync — MUST go
 * through {@link #acquire()}/{@link #release()} around it. There is deliberately no
 * way to bypass this from a call site; SecEdgarClient is the only class that talks to
 * SEC EDGAR directly, and it always wraps its calls here, so compliance is structural
 * rather than a rule every future call site has to remember.
 */
@Component
public class SecRateLimiter {

    private final Semaphore concurrencyGate;
    private final long minIntervalMs;
    private final AtomicLong lastRequestStartedAtMs = new AtomicLong(0);

    public SecRateLimiter(TrueSightProperties properties) {
        this.concurrencyGate = new Semaphore(properties.sec().maxConcurrentRequests());
        this.minIntervalMs = properties.sec().minRequestIntervalMs();
    }

    /**
     * Blocks until it is this caller's turn to make one SEC request. Callers MUST call
     * {@link #release()} in a finally block after the request completes (success or
     * failure) — see SecEdgarClient for the paired usage.
     */
    public void acquire() throws InterruptedException {
        concurrencyGate.acquire();
        waitForPacingSlot();
    }

    public void release() {
        concurrencyGate.release();
    }

    private void waitForPacingSlot() throws InterruptedException {
        while (true) {
            long now = System.currentTimeMillis();
            long last = lastRequestStartedAtMs.get();
            long elapsed = now - last;
            if (elapsed >= minIntervalMs) {
                // Only claim the slot if nobody else claimed it between our read and
                // this write — compareAndSet makes the "has enough time passed, then
                // claim it" check atomic across concurrently racing callers.
                if (lastRequestStartedAtMs.compareAndSet(last, now)) {
                    return;
                }
                // Someone else claimed the slot first; loop and re-check timing.
            } else {
                Thread.sleep(minIntervalMs - elapsed);
            }
        }
    }
}
