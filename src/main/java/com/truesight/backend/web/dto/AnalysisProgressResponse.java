package com.truesight.backend.web.dto;

import com.truesight.backend.ingestion.AnalysisProgressTracker;

public record AnalysisProgressResponse(int total, int completed, int failed, boolean cancelled, boolean done) {

    public static AnalysisProgressResponse from(AnalysisProgressTracker.Progress p) {
        return new AnalysisProgressResponse(p.total(), p.completed(), p.failed(), p.cancelled(), p.done());
    }
}
