package com.truesight.backend.web.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.List;

/**
 * AC 2.1: "unrecognised... can be skipped or corrected" — excludedTickers is how the
 * frontend tells the backend which previewed rows the user chose to drop before
 * applying (typically the unrecognised ones, but not necessarily limited to those).
 */
public record ConfirmUploadRequest(
        @NotBlank String csvContent,
        List<String> excludedTickers
) {
}
