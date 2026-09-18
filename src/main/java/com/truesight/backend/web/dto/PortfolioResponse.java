package com.truesight.backend.web.dto;

import com.truesight.backend.domain.Portfolio;
import java.time.Instant;

public record PortfolioResponse(Long id, String name, Instant createdAt, Instant lastAnalysedAt) {

    public static PortfolioResponse from(Portfolio p) {
        return new PortfolioResponse(p.getId(), p.getName(), p.getCreatedAt(), p.getLastAnalysedAt());
    }
}
