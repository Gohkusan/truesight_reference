package com.truesight.backend.web.dto;

import com.truesight.backend.domain.ReviewStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** AC 5.4: CONFIRMED / REJECTED / PENDING (to reverse), with an optional note. */
public record ReviewRequest(@NotNull ReviewStatus status, @Size(max = 1000) String note) {
}
