package com.truesight.backend.web.dto;

import jakarta.validation.constraints.NotBlank;

public record AddByTickerRequest(@NotBlank String ticker) {
}
