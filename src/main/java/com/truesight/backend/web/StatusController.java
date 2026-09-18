package com.truesight.backend.web;

import com.truesight.backend.ingestion.llm.LlmHealthTracker;
import com.truesight.backend.ingestion.sec.SecTickerIndexService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AC 9.2 / AC 10.1: what the global banners read. Deliberately reports only the
 * facts (is the LLM configured, what kind of failure was last seen and when, is the
 * ticker index loaded) — the frontend decides which banner to show and how to word it.
 */
@RestController
@RequestMapping("/api/status")
@Tag(name = "Status", description = "Service health for the global banners (AC 9.2, 10.1).")
public class StatusController {

    private final LlmHealthTracker llmHealthTracker;
    private final SecTickerIndexService tickerIndexService;

    public StatusController(LlmHealthTracker llmHealthTracker, SecTickerIndexService tickerIndexService) {
        this.llmHealthTracker = llmHealthTracker;
        this.tickerIndexService = tickerIndexService;
    }

    public record Status(LlmHealthTracker.Status llm, boolean secTickerIndexLoaded, Instant secTickerIndexLoadedAt, Instant now) {
    }

    @GetMapping
    @Operation(summary = "LLM and SEC index status for banners")
    public Status status() {
        return new Status(llmHealthTracker.status(), tickerIndexService.isLoaded(),
                tickerIndexService.getLastLoadedAt(), Instant.now());
    }
}
