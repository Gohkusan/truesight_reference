package com.truesight.backend.web.dto;

import jakarta.validation.constraints.NotBlank;

public record CreatePortfolioRequest(@NotBlank String name) {
}
