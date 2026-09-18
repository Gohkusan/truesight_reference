package com.truesight.backend.web.dto;

import com.truesight.backend.domain.DismissReason;
import jakarta.validation.constraints.NotNull;

/** AC 7.3: "Dismiss asks for a reason (not relevant, duplicate, wrong company)." */
public record DismissRequest(@NotNull DismissReason reason) {
}
